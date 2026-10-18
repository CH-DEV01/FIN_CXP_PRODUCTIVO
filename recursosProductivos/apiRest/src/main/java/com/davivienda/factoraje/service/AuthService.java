package com.davivienda.factoraje.service;

import com.davivienda.factoraje.dto.auth.UserProfileResponseDTO;

public interface AuthService {

    /** Perfil del usuario autenticado y vigencia del token con el que se autenticó. */
    UserProfileResponseDTO getCurrentUserProfile(String token);

    /** Emite un token nuevo para la sesión del usuario autenticado. */
    String refreshSession(String token);

    /** Invalida todos los tokens emitidos hasta ahora para el usuario autenticado, si lo hay. */
    void logout();
}
