package com.skyshift.cognitiveragengine.auth.service;

import com.skyshift.cognitiveragengine.user.mapper.UserMapper;
import com.skyshift.cognitiveragengine.user.model.AuthenticatedUser;
import com.skyshift.cognitiveragengine.user.model.entity.UserEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public CustomUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return new AuthenticatedUser(
            user.getId(),
            user.getGroupId(),
            user.getUsername(),
            user.getPasswordHash(),
            user.getRole(),
            Boolean.TRUE.equals(user.getEnabled())
        );
    }
}
