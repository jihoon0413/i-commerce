package com.example.i_commerce.domain.order.repository;

import com.example.i_commerce.domain.order.entity.Payment;
import com.example.i_commerce.domain.order.entity.emuns.PaymentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findAllByPayStatus(PaymentStatus paymentStatus);

    List<Payment> findAllByPayStatusAndUpdatedAtBefore(PaymentStatus paymentStatus, LocalDateTime updatedAt);

    @Query("select p from Payment p join fetch p.order where p.tossOrderId = :tossOrderId")
    Optional<Payment> findByTossOrderIdWithOrder(@Param("tossOrderId") String tossOrderId);

    // READY 상태인 결제만 PROCESSING으로 전이시키는 조건부(원자적) 업데이트.
    // 영향받은 행이 1건인 요청만 결제 승인 선점에 성공한 것으로 간주한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.payStatus = :processing, p.pgTid = :pgTid, p.updatedAt = CURRENT_TIMESTAMP "
        + "where p.id = :id and p.payStatus = :ready")
    int markProcessingIfReady(@Param("id") Long id, @Param("pgTid") String pgTid,
        @Param("ready") PaymentStatus ready, @Param("processing") PaymentStatus processing);
}
