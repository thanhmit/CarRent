package com.carrent.notification;

import java.time.LocalDateTime;

public record NotificationDto(Long id, String title, String message, String type, boolean read,
                              String relatedEntityType, Long relatedEntityId, LocalDateTime createdAt) {
}
