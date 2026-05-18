package com.dayworks_ltd.loyalty_engine.auth.model;

import com.dayworks_ltd.loyalty_engine.auth.enums.Status;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class CustomUserDetails implements UserDetails {
    private User user;

    public CustomUserDetails(User user){this.user = user;}

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.getRole().getAuthorities();
    }

    public Long getUserId(){return this.user.getUserId();}

    public String getUserRole(){return this.user.getRole().name();}

    @Override
    public String getUsername(){return this.user.getUsername();}

    @Override
    public String getPassword(){return this.user.getPassword();}

    @Override
    public boolean isAccountNonExpired(){return true;}

    @Override
    public boolean isAccountNonLocked(){return true;}

    @Override
    public boolean isCredentialsNonExpired(){return true;}

    @Override
    public boolean isEnabled(){

        return user.getStatus() == Status.ACTIVE; }

    public String getBusinessType() {
        if (user.getMerchant() == null) {
            return null;
        }
        return user.getMerchant().getBusinessType();
    }

    public Boolean getIsWholesaler() {
        return user.getIsWholesaler();
    }
}
