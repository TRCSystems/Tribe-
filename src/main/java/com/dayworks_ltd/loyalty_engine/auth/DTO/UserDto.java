package com.dayworks_ltd.loyalty_engine.auth.DTO;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Builder
public class UserDto {
    private String username;
    private String password;
    private String role;
    private String status;
    private String merchantId=null;
    private Boolean isWholesaler = false;
}
