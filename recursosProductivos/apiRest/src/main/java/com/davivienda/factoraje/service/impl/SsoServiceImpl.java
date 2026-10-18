package com.davivienda.factoraje.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.davivienda.factoraje.domain.entities.PendingSessionModel;
import com.davivienda.factoraje.domain.entities.SystemParameterModel;
import com.davivienda.factoraje.domain.entities.UserModel;
import com.davivienda.factoraje.domain.enums.GeneralStatusEnum;
import com.davivienda.factoraje.domain.enums.SystemParameterKey;
import com.davivienda.factoraje.dto.sso.DataSession;
import com.davivienda.factoraje.dto.sso.HandoffDTOResponse;
import com.davivienda.factoraje.dto.sso.OtcDTORequest;
import com.davivienda.factoraje.dto.sso.OtcDTOResponse;
import com.davivienda.factoraje.infrastructure.exception.BusinessException;
import com.davivienda.factoraje.infrastructure.exception.ResourceNotFoundException;
import com.davivienda.factoraje.infrastructure.exception.UnauthorizedAccessException;
import com.davivienda.factoraje.infrastructure.security.JwtService;
import com.davivienda.factoraje.infrastructure.util.IdentifierNormalizer;
import com.davivienda.factoraje.repository.ParameterRepository;
import com.davivienda.factoraje.repository.PendingSessionRepository;
import com.davivienda.factoraje.repository.UserRepository;
import com.davivienda.factoraje.service.PendingSessionService;
import com.davivienda.factoraje.service.SsoService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SsoServiceImpl implements SsoService {

    private static final int MAX_OTC_LENGTH = 512;

    private final ParameterRepository parameterRepository;
    private final PendingSessionRepository pendingSessionRepository;
    private final PendingSessionService pendingSessionService;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;
    private final JwtService jwtService;

    public SsoServiceImpl(
            ParameterRepository parameterRepository,
            RestTemplate restTemplate,
            PendingSessionRepository pendingSessionRepository,
            PendingSessionService pendingSessionService,
            UserRepository userRepository,
            JwtService jwtService) {
        this.parameterRepository = parameterRepository;
        this.restTemplate = restTemplate;
        this.pendingSessionRepository = pendingSessionRepository;
        this.pendingSessionService = pendingSessionService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        log.info("SsoServiceImpl initialized");
    }

    @Override
    public HandoffDTOResponse processHandoff(String appCode, String otc, String bearerTokenRequest) {

        log.info("[Handoff-Start] Iniciando proceso para AppCode: {}", appCode);

        try {
            // 1. CARGA DE PARÁMETROS (Configuración)
            SystemParameterModel myBearerToken = parameterRepository.findByKey(SystemParameterKey.BEARER_TOKEN.name())
                    .orElseThrow(() -> new RuntimeException("Bearer token not found"));

            SystemParameterModel payBearerToken = parameterRepository
                    .findByKey(SystemParameterKey.PAY_BEARER_TOKEN.name())
                    .orElseThrow(() -> new RuntimeException("Pay bearer token not found"));

            SystemParameterModel myAppCode = parameterRepository.findByKey(SystemParameterKey.APP_CODE.name())
                    .orElseThrow(() -> new RuntimeException("App code not found"));

            SystemParameterModel payDaviviendaUrl = parameterRepository
                    .findByKey(SystemParameterKey.PAY_DAVIVIENDA_URL.name())
                    .orElseThrow(() -> new RuntimeException("Pay Davivienda URL not found"));

            String daviviendaUrl = payDaviviendaUrl.getValue();
            log.info("url para petición: {}", daviviendaUrl);

            // 2. VALIDACIONES DE SEGURIDAD
            // Sin este control, "Bearer CONFIGURAR" pasaría la validación en una base recién instalada.
            if (!configured(myBearerToken) || !configured(myAppCode) || !configured(payBearerToken)
                    || !configured(payDaviviendaUrl)) {
                log.error("[Handoff-Config] Los parámetros de SSO siguen en CONFIGURAR; el handoff está deshabilitado.");
                throw new BusinessException("La integración SSO no está configurada");
            }

            if (!secretEquals("Bearer " + myBearerToken.getValue(), bearerTokenRequest)) {
                log.warn("[Handoff-Business] Intento de acceso no autorizado. Token inválido.");
                throw new BusinessException("No autorizado: Bearer Token inválido");
            }

            if (myAppCode.getValue() == null || !secretEquals(myAppCode.getValue(), appCode)) {
                log.warn("[Handoff-Business] AppCode no coincide.");
                throw new BusinessException("Código de aplicación incorrecto");
            }

            if (!validOtc(otc)) {
                log.warn("[Handoff-Business] OTC con formato inválido.");
                throw new BusinessException("OTC con formato inválido");
            }

            // 3. PREPARACIÓN DE PETICIÓN EXTERNA
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Authorization", "Bearer " + payBearerToken.getValue());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<OtcDTORequest> requestEntity = new HttpEntity<>(new OtcDTORequest(otc), headers);

            log.info("[Handoff-External] Consultando validación en Davivienda...");

            // 4. LLAMADA AL SERVICIO EXTERNO
            ResponseEntity<OtcDTOResponse> response = restTemplate.exchange(
                    daviviendaUrl,
                    HttpMethod.POST,
                    requestEntity,
                    OtcDTOResponse.class);

            OtcDTOResponse otcResponse = response.getBody();

            // 5. VALIDACIÓN DE RESPUESTA DE NEGOCIO (Proveedor Externo)
            if (otcResponse == null || !Boolean.TRUE.equals(otcResponse.getSuccess())) {
                log.warn("[Handoff-Business] El proveedor rechazó la solicitud o envió respuesta vacía.");
                throw new BusinessException("El OTC no pudo ser procesado por el proveedor");
            }

            String status = otcResponse.getData().getStatus();
            if (!"validated".equals(status)) {
                log.warn("[Handoff-Business] OTC no validado. Status devuelto: {}", status);
                throw new BusinessException("OTC no tiene estado 'validated'");
            }

            // 6. PROCESAMIENTO EXITOSO Y PERSISTENCIA
            String email = otcResponse.getData().getUser();
            String sessionToken = UUID.randomUUID().toString();

            PendingSessionModel pendingSession = new PendingSessionModel();
            pendingSession.setSessionToken(sessionToken);
            pendingSession.setUserEmail(email);
            pendingSession.setCreatedAt(LocalDateTime.now());
            pendingSession.setExpiresAt(LocalDateTime.now().plusMinutes(5));

            pendingSessionRepository.save(pendingSession);
            log.info("[Handoff-Success] Sesión creada para el E-mail: {}", email);

            // 7. CONSTRUCCIÓN DE RESPUESTA
            HandoffDTOResponse handoffResponse = new HandoffDTOResponse();
            handoffResponse.setSuccess(true);
            DataSession session = new DataSession();
            session.setStatus("ok");
            session.setToken(sessionToken);
            session.setTimestamp(OffsetDateTime.now());
            handoffResponse.setData(session);

            return handoffResponse;

        } catch (BusinessException e) {
            // AQUÍ CAEN: Tokens inválidos, AppCode erróneo, OTC no validado por Davivienda.
            log.warn("[Handoff-Logic-Error] Error de regla de negocio: {}", e.getMessage());
            throw e;

        } catch (HttpStatusCodeException e) {
            // AQUÍ CAEN: Errores HTTP (404, 500, 401 del servidor de Davivienda).
            log.error("[Handoff-External-Error] Error de comunicación (HTTP {}). Response: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Error en comunicación con proveedor externo");

        } catch (ResourceAccessException e) {
            // AQUÍ CAEN: Pay Davivienda no conecta o no responde dentro de los 15 segundos.
            log.error("[Handoff-External-Error] Pay Davivienda no respondió a tiempo", e);
            throw new RuntimeException("Error en comunicación con proveedor externo");

        } catch (Exception e) {
            // AQUÍ CAEN: NullPointer, errores de base de datos, errores inesperados.
            log.error("[Handoff-Critical-Error] Error no controlado en el proceso", e);
            throw new RuntimeException("Error interno del sistema: " + e.getMessage());
        }
    }

    private static boolean configured(SystemParameterModel parameter) {
        String value = parameter.getValue();
        return value != null && !value.isBlank() && !SystemParameterKey.UNCONFIGURED.equals(value.trim());
    }

    /** El formato del OTC lo define Davivienda; aquí solo se descarta lo que no puede serlo. */
    static boolean validOtc(String otc) {
        return otc != null && !otc.isBlank() && otc.length() <= MAX_OTC_LENGTH
                && otc.chars().noneMatch(Character::isISOControl);
    }

    /** Compara en tiempo constante para no revelar cuántos caracteres coinciden. */
    static boolean secretEquals(String expected, String received) {
        if (expected == null || received == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional
    public String activateSession(String sessionToken) {
        // El borrado corre en REQUIRES_NEW: si la validación falla, el token ya no se puede reusar.
        PendingSessionModel session = pendingSessionService.consume(sessionToken);

        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            log.warn("[Login] Sesión rechazada: el token de activación expiró");
            throw new RuntimeException("session_expired");
        }

        // Misma norma que al registrar (requireEmail → solo trim): se conserva mayúsculas y guiones.
        UserModel user = userRepository.findByEmail(IdentifierNormalizer.trimmed(session.getUserEmail()))
                .orElseThrow(() -> {
                    log.warn("[Login] Sesión rechazada: el usuario no está registrado");
                    return new ResourceNotFoundException("Usuario no registrado en el sistema local.");
                });

        if (user.getStatus() != GeneralStatusEnum.ACTIVE) {
            log.warn("[Login] Sesión rechazada para el usuario {}: se encuentra inactivo", user.getId());
            throw new UnauthorizedAccessException("El usuario se encuentra inactivo.");
        }
        if (!user.isEnabled()) {
            log.warn("[Login] Sesión rechazada para el usuario {}: su entidad se encuentra inactiva", user.getId());
            throw new UnauthorizedAccessException(
                    "La entidad a la que pertenece el usuario se encuentra inactiva. Contacte al administrador.");
        }

        String jwt = jwtService.generateToken(user);
        log.info("[Login] Sesión iniciada por el usuario {}", user.getId());
        return jwt;
    }
}
