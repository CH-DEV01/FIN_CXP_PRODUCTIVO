package com.davivienda.factoraje.service.impl;

import com.davivienda.factoraje.domain.catalogs.RoleCat;
import com.davivienda.factoraje.domain.entities.UserModel;
import com.davivienda.factoraje.domain.enums.GeneralStatusEnum;
import com.davivienda.factoraje.domain.enums.SystemParameterKey;
import com.davivienda.factoraje.dto.auth.MenuDTO;
import com.davivienda.factoraje.dto.auth.RouteDTO;
import com.davivienda.factoraje.dto.auth.UserProfileResponseDTO;
import com.davivienda.factoraje.infrastructure.config.SystemParameters;
import com.davivienda.factoraje.infrastructure.security.CurrentUserService;
import com.davivienda.factoraje.infrastructure.security.JwtService;
import com.davivienda.factoraje.repository.RoleMenuRepository;
import com.davivienda.factoraje.repository.RoleRouteRepository;
import com.davivienda.factoraje.repository.UserRepository;
import com.davivienda.factoraje.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final String UNAUTHORIZED_ROUTE = "/unauthorized";

    private final UserRepository userRepository;
    private final RoleRouteRepository roleRouteRepository;
    private final RoleMenuRepository roleMenuRepository;
    private final JwtService jwtService;
    private final SystemParameters systemParameters;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponseDTO getCurrentUserProfile(String token) {
        UserModel user = currentUserService.get();

        // Ruta base, rutas y menús del rol, administrados en roles_cat, role_routes y role_menus
        RoleCat role = user.getRole();
        boolean activeRole = role.getStatus() == GeneralStatusEnum.ACTIVE;

        String defaultRoute = activeRole && role.getDefaultRoute() != null && !role.getDefaultRoute().isBlank()
                ? role.getDefaultRoute()
                : UNAUTHORIZED_ROUTE;
        List<RouteDTO> routes = activeRole ? roleRouteRepository.findActiveRoutesByRole(role.getId()) : List.of();
        List<MenuDTO> menus = activeRole ? roleMenuRepository.findActiveMenusByRole(role.getId()) : List.of();

        return new UserProfileResponseDTO(
                user.getId(),
                user.getDui(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                role.getName(),
                defaultRoute,
                user.getEntity() != null ? user.getEntity().getId() : null,
                user.getEntity() != null ? user.getEntity().getName() : null,
                List.of(),
                menus,
                routes,
                systemParameters.getInt(SystemParameterKey.SESSION_IDLE_TIMEOUT_MINUTES),
                jwtService.window(token)
            );
    }

    @Override
    public String refreshSession(String token) {
        return jwtService.refreshToken(currentUserService.get(), token);
    }

    @Override
    @Transactional
    public void logout() {
        currentUserService.find().ifPresent(user -> {
            userRepository.revokeSessions(user.getId(), Instant.now());
            log.info("[Logout] Sesión cerrada por el usuario {}", user.getId());
        });
    }
}
