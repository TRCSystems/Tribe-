package com.dayworks_ltd.loyalty_engine.auth.repository;

import com.dayworks_ltd.loyalty_engine.auth.model.User;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    @Modifying
    @Transactional
    @NativeQuery(
            value = "INSERT INTO user (username, password, role, status, merchant_id) " +
                    "VALUES (:username, :password, :role, :status, :merchantId)"
    )
    int addUser(@Param("username") String username,
                @Param("password") String password,
                @Param("role") String role,
                @Param("status") String status,
                @Param("merchantId")String merchantId);

    @NativeQuery(
            value = "SELECT * " +
                    "FROM user"
    )
    List<User> getAllUsers();


    @NativeQuery(
            value = "SELECT * " +
                    "FROM user " +
                    "WHERE role = :role"
    )
    List<User> getUsersByRole(@Param("role") String role);

    @NativeQuery(
            value = "SELECT * " +
                    "FROM user " +
                    "WHERE user_id = :userId"
    )
    User getUserById(@Param("userId") Long userId);

    @NativeQuery(
            value = "SELECT * " +
                    "FROM user " +
                    "WHERE username = :username"
    )
    User getUserByUsername(@Param("username") String username);


    @Modifying
    @Transactional
    @NativeQuery(
            value = "UPDATE user " +
                    "SET username = :newUsername " +
                    "WHERE user_id = :userId"
    )
    int updateUsername( @Param("userId") Long userId,
                        @Param("newUsername") String newUsername);

    @Modifying
    @Transactional
    @NativeQuery(
            value = "UPDATE user " +
                    "SET password = :newPassword " +
                    "WHERE user_id = :userId"
    )
    int updatePassword( @Param("userId") Long userId,
                        @Param("newPassword") String newPassword);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.merchant WHERE u.username = :username")
    User findByUsernameWithMerchant(@Param("username") String username);

    @Modifying
    @Transactional
    @NativeQuery(
            value = "UPDATE user " +
                    "SET role = :newRole " +
                    "WHERE user_id = :userId"
    )
    int updateRole( @Param("userId") Long userId,
                        @Param("newRole") String newRole);

    @Modifying
    @Transactional
    @NativeQuery(
            value = "UPDATE user " +
                    "SET status = :newStatus " +
                    "WHERE user_id = :userId"
    )
    int updateStatus( @Param("userId") Long userId,
                        @Param("newStatus") String newStatus);
}
