package com.carrent.notification;

import com.carrent.common.exception.BusinessException;
import com.carrent.common.exception.ForbiddenException;
import com.carrent.common.exception.NotFoundException;
import com.carrent.user.User;
import com.carrent.user.UserRepository;
import com.carrent.user.UserStatus;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Set<String> TYPES = Set.of("CAR_PENDING_APPROVAL", "CAR_APPROVED", "CAR_REJECTED",
            "BOOKING_CREATED", "BOOKING_CONFIRMED", "BOOKING_REJECTED", "BOOKING_CANCELLED", "BOOKING_COMPLETED",
            "PAYMENT_PAID", "PAYMENT_FAILED", "REFUND_WAITING_RECIPIENT", "REFUND_SUCCEEDED", "REFUND_WAITING_FUNDS", "REFUND_OVERDUE");

    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Transactional
    public void notify(Long userId, String type, String title, String message,
                       String relatedEntityType, Long relatedEntityId) {
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException("Loại thông báo không hợp lệ.");
        }
        validateText(title, 150);
        validateText(message, 1000);
        if (relatedEntityType != null && !Set.of("CAR", "BOOKING", "PAYMENT", "REFUND").contains(relatedEntityType)
                || (relatedEntityType == null) != (relatedEntityId == null)
                || relatedEntityId != null && relatedEntityId <= 0) {
            throw new BusinessException("Tham chiếu thông báo không hợp lệ.");
        }
        User recipient = userId == null ? null : users.findById(userId).orElse(null);
        if (recipient == null) {
            throw new NotFoundException("Người nhận thông báo không tồn tại.");
        }
        Notification notification = new Notification();
        notification.setUser(recipient);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRelatedEntityType(relatedEntityType);
        notification.setRelatedEntityId(relatedEntityId);
        notifications.save(notification);
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> getNotifications(Long userId, Pageable pageable) {
        requireActiveUser(userId);
        return notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(notification ->
                new NotificationDto(notification.getId(), notification.getTitle(), notification.getMessage(),
                        notification.getType(), notification.isRead(), notification.getRelatedEntityType(),
                        notification.getRelatedEntityId(), notification.getCreatedAt()));
    }

    @Transactional
    public void markRead(Long id, Long userId) {
        requireActiveUser(userId);
        Notification notification = id == null ? null : notifications.findByIdAndUserId(id, userId).orElse(null);
        if (notification == null) {
            throw new NotFoundException("Thông báo không tồn tại.");
        }
        notification.setRead(true);
    }

    @Transactional
    public void markAllRead(Long userId) {
        requireActiveUser(userId);
        notifications.findByUserIdAndReadFalse(userId).forEach(notification -> notification.setRead(true));
    }

    @Transactional(readOnly = true)
    public long countUnread(Long userId) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        return user != null && user.getStatus() == UserStatus.ACTIVE
                ? notifications.countByUserIdAndReadFalse(userId) : 0;
    }

    private void requireActiveUser(Long userId) {
        User user = userId == null ? null : users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Tài khoản không được phép truy cập thông báo.");
        }
    }

    private static void validateText(String text, int maxLength) {
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new BusinessException("Nội dung thông báo không hợp lệ.");
        }
    }
}
