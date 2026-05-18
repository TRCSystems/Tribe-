package com.dayworks_ltd.loyalty_engine.auth.services;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import com.dayworks_ltd.loyalty_engine.auth.model.User;
import com.dayworks_ltd.loyalty_engine.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    @Autowired
    private UserRepository userRepository;


    @Override
    public CustomUserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameWithMerchant(username); // changed

        if (Objects.isNull(user)) {
            throw new UsernameNotFoundException("username, " + username + ", not found!");
        }

        return new CustomUserDetails(user);
    }

//    @Override
//    public CustomUserDetails loadUserByUsername(String username) throws UsernameNotFoundException
//    {
//        User user = userRepository.getUserByUsername(username);
//
//        if(Objects.isNull( user ) )
//        {
//            throw new UsernameNotFoundException("username, " + username + ", not found!");
//        }
//
//        return new CustomUserDetails(user);
//    }

}
