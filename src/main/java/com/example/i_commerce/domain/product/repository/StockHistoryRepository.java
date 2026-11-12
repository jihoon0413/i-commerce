package com.example.i_commerce.domain.product.repository;

import com.example.i_commerce.domain.product.entity.StockHistory;
import com.example.i_commerce.domain.product.repository.projection.StockDeductHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    @Query("""
    SELECT COUNT(sh) > 0
    FROM StockHistory sh
    WHERE sh.orderId = :orderId
    AND sh.changeType = 'DEDUCT'
    """)
    boolean existsDeductHistoryByOrderId(@Param("orderId") Long orderId);

    @Query("""
    SELECT COUNT(sh) > 0
    FROM StockHistory sh
    WHERE sh.orderId = :orderId
    AND sh.changeType = 'RESTORE'
    """)
    boolean existsRestoreHistoryByOrderId(@Param("orderId") Long orderId);

    @Query("""
    SELECT new com.example.i_commerce.domain.product.repository.projection.StockDeductHistory(
        pi.id,
        sh.changeQuantity
    )
    FROM StockHistory sh
    JOIN sh.stock s
    JOIN s.productItem pi
    WHERE sh.orderId = :orderId
    AND sh.changeType = 'DEDUCT'
    """)
    List<StockDeductHistory> findDeductHistoriesByOrderId(@Param("orderId") Long orderId);

    @Query("""
    SELECT sh FROM StockHistory sh
    JOIN FETCH sh.stock s
    JOIN FETCH s.productItem pi
    WHERE pi.id = :productItemId
    """)
    List<StockHistory> findAllByProductItemId(@Param("productItemId") Long productItemId);

}
