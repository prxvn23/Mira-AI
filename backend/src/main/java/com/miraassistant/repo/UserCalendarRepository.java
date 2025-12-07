package com.miraassistant.repo;

import com.miraassistant.model.UserCalendar;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface UserCalendarRepository extends MongoRepository<UserCalendar, String> {

    // Detect duplicates
    List<UserCalendar> findByEmail(String email);
    List<UserCalendar> findByPhoneNumber(String phoneNumber);

    // For GoogleService → Must return ONLY ONE RECORD
    Optional<UserCalendar> findFirstByEmail(String email);
    Optional<UserCalendar> findFirstByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);
    boolean existsByPhoneNumber(String phoneNumber);
}
