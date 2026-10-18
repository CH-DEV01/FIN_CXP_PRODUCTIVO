package com.davivienda.factoraje.dto.sso;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtcDTORequest {

    @ToString.Exclude
    private String otc;

}
