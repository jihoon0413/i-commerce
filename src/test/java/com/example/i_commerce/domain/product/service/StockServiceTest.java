package com.example.i_commerce.domain.product.service;



import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.example.i_commerce.domain.product.application.service.StockService;
import com.example.i_commerce.domain.product.application.dto.StockDeductCommand;
import com.example.i_commerce.domain.product.entity.ProductItem;
import com.example.i_commerce.domain.product.entity.Stock;
import com.example.i_commerce.domain.product.event.StockDepletedEvent;
import com.example.i_commerce.domain.product.exception.ProductErrorCode;
import com.example.i_commerce.domain.product.repository.StockHistoryRepository;
import com.example.i_commerce.domain.product.repository.StockRepository;
import com.example.i_commerce.domain.product.repository.projection.StockDeductHistory;
import com.example.i_commerce.global.exception.AppException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockService 단위 테스트")
public class StockServiceTest {

    @InjectMocks
    private StockService stockService;

    @Mock
    private StockRepository stockRepository;

    @Mock
    private StockHistoryRepository stockHistoryRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<List<Long>> idsCaptor;

    @Captor
    private ArgumentCaptor<StockDepletedEvent> eventCaptor;


    private Stock createMockStock(Long productItemId) {
        Stock stock = mock(Stock.class);
        ProductItem productItem = mock(ProductItem.class);

        given(stock.getProductItem()).willReturn(productItem);
        given(productItem.getId()).willReturn(productItemId);

        return stock;
    }

    @Nested
    @DisplayName("재고 차감 테스트")
    class DeductStocksTest {

        @Test
        @DisplayName("단일 상품의 재고를 정상적으로 차감한다.")
        void deductStocks_success_singleProduct() {
            // given
            Long productItemId = 1L;
            Long orderId = 100L;
            int deductQuantity = 3;

            Stock stock = createMockStock(productItemId);

            List<StockDeductCommand> commands = List.of(
                new StockDeductCommand(productItemId, deductQuantity, orderId)
            );

            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock));
            given(stock.isOutOfStock()).willReturn(false);

            // when
            stockService.deductStocks(commands);

            // then
            then(stock).should(times(1)).deduct(deductQuantity, orderId);
            then(eventPublisher).should(never()).publishEvent(any(StockDepletedEvent.class));
        }

        @Test
        @DisplayName("여러 상품의 재고를 졍상적으로 차감한다.")
        void deductStocks_success_multipleProducts() {
            // given
            Long productItemId1 = 1L;
            Long productItemId2 = 2L;
            Long orderId = 100L;
            int deductQuantity1 = 3;
            int deductQuantity2 = 5;

            Stock stock1 = createMockStock(productItemId1);
            Stock stock2 = createMockStock(productItemId2);

            List<StockDeductCommand> commands = List.of(
                new StockDeductCommand(productItemId1, deductQuantity1, orderId),
                new StockDeductCommand(productItemId2, deductQuantity2, orderId)
            );

            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock1, stock2));
            given(stock1.isOutOfStock()).willReturn(false);
            given(stock2.isOutOfStock()).willReturn(false);

            // when
            stockService.deductStocks(commands);

            // then
            then(stock1).should(times(1)).deduct(deductQuantity1, orderId);
            then(stock2).should(times(1)).deduct(deductQuantity2, orderId);
            then(eventPublisher).should(never()).publishEvent(any(StockDepletedEvent.class));
        }

        @Test
        @DisplayName("재고 차감 후 품절 상태가 된 상품이 존재하면 StockDepletedEvent를 발행한다.")
        void deductStocks_success_publishesEvent() {
            // given
            Long productItemId1 = 1L;
            Long productItemId2 = 2L;
            Long orderId = 100L;

            StockDeductCommand command1 = new StockDeductCommand(
                productItemId1, 5, orderId
            );
            StockDeductCommand command2 = new StockDeductCommand(
                productItemId2, 5, orderId
            );

            Stock stock1 = createMockStock(productItemId1);
            Stock stock2 = createMockStock(productItemId2);

            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock1, stock2));

            given(stock1.isOutOfStock()).willReturn(true);
            given(stock2.isOutOfStock()).willReturn(false);

            // when
            stockService.deductStocks(List.of(command1, command2));

            // then
            then(eventPublisher)
                .should(times(1)).publishEvent(eventCaptor.capture());
            StockDepletedEvent publishedEvent = eventCaptor.getValue();
            assertThat(publishedEvent.productItemIds()).containsExactly(productItemId1);
        }

        @Test
        @DisplayName("재고 차감시 ID가 정렬되어 락 조회가 요청된다.")
        void deductStocks_success_sortedIds() {
            // given
            Long orderId = 100L;

            StockDeductCommand command1 = new StockDeductCommand(
                3L, 1, orderId
            );
            StockDeductCommand command2 = new StockDeductCommand(
                1L, 1, orderId
            );
            StockDeductCommand command3 = new StockDeductCommand(
                2L, 1, orderId
            );

            Stock stock1 = createMockStock(1L);
            Stock stock2 = createMockStock(2L);
            Stock stock3 = createMockStock(3L);

            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock1, stock2, stock3));

            given(stock1.isOutOfStock()).willReturn(false);
            given(stock2.isOutOfStock()).willReturn(false);
            given(stock3.isOutOfStock()).willReturn(false);

            // when
            stockService.deductStocks(List.of(command1, command2, command3));

            // then
            then(stockRepository).should()
                .findAllByProductItemIdsWithLock(idsCaptor.capture());
            List<Long> capturedIds = idsCaptor.getValue();
            assertThat(capturedIds).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("같은 주문의 재고 차감 이력이 있으면 중복 차감하지 않는다.")
        void deductStocks_skipsDuplicateOrderDeduction() {
            Long productItemId = 1L;
            Long orderId = 100L;
            Stock stock = createMockStock(productItemId);
            List<StockDeductCommand> commands = List.of(
                new StockDeductCommand(productItemId, 3, orderId)
            );

            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock));
            given(stockHistoryRepository.existsDeductHistoryByOrderId(orderId))
                .willReturn(true);

            stockService.deductStocks(commands);

            then(stock).should(never()).deduct(anyInt(), anyLong());
            then(eventPublisher).should(never()).publishEvent(any(StockDepletedEvent.class));
        }

        @Test
        @DisplayName("재고가 조회되지 않으면 예외가 발생한다.")
        void deductStocks_fail_stockNotFound() {
            // given
            StockDeductCommand command = new StockDeductCommand(
                1L, 5, 100L
            );
            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(Collections.emptyList());

            // when & then
            AppException exception = assertThrows(AppException.class, () ->
                stockService.deductStocks(List.of(command))
            );
            assertThat(exception.getErrorCode())
                .isEqualTo(ProductErrorCode.STOCK_NOT_FOUND);
        }

    }

    @Nested
    @DisplayName("재고 롤백 테스트")
    class RollbackStocksTest {

        @Test
        @DisplayName("재고가 정상적으로 복구된다.")
        void rollbackStocks_success() {
            // given
            Long orderId = 100L;
            Long productItemId1 = 1L;
            Long productItemId2 = 2L;

            StockDeductHistory history1 = new StockDeductHistory(productItemId1, 3);
            StockDeductHistory history2 = new StockDeductHistory(productItemId2, 2);

            Stock stock1 = createMockStock(productItemId1);
            Stock stock2 = createMockStock(productItemId2);

            given(stockHistoryRepository.findDeductHistoriesByOrderId(orderId))
                .willReturn(List.of(history1, history2));
            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock1, stock2));
            given(stockHistoryRepository.existsRestoreHistoryByOrderId(orderId))
                .willReturn(false);

            // when
            stockService.rollbackStocks(orderId);

            // then
            then(stock1).should(times(1)).restore(3, orderId);
            then(stock2).should(times(1)).restore(2, orderId);
        }

        @Test
        @DisplayName("재고 복구시 ID가 정렬되어 락 조회가 요청된다.")
        void rollbackStocks_success_sortedIds() {
            // given
            Long orderId = 100L;

            StockDeductHistory history1 = new StockDeductHistory(3L, 1);
            StockDeductHistory history2 = new StockDeductHistory(1L, 1);
            StockDeductHistory history3 = new StockDeductHistory(2L, 1);

            Stock stock1 = createMockStock(1L);
            Stock stock2 = createMockStock(2L);
            Stock stock3 = createMockStock(3L);

            given(stockHistoryRepository.findDeductHistoriesByOrderId(orderId))
                .willReturn(List.of(history1, history2, history3));
            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock1, stock2, stock3));
            given(stockHistoryRepository.existsRestoreHistoryByOrderId(orderId))
                .willReturn(false);

            // when
            stockService.rollbackStocks(orderId);

            // then
            then(stockRepository).should()
                .findAllByProductItemIdsWithLock(idsCaptor.capture());
            assertThat(idsCaptor.getValue()).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("이미 복구된 주문 ID로 요청시 예외가 발생한다.")
        void rollbackStocks_fail_stockAlreadyRestored() {
            // given
            Long orderId = 100L;
            Long productItemId = 1L;

            StockDeductHistory history = new StockDeductHistory(productItemId, 3);
            Stock stock = createMockStock(productItemId);

            given(stockHistoryRepository.findDeductHistoriesByOrderId(orderId))
                .willReturn(List.of(history));
            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock));
            given(stockHistoryRepository.existsRestoreHistoryByOrderId(orderId))
                .willReturn(true);

            // when & then
            AppException exception = assertThrows(AppException.class, () ->
                stockService.rollbackStocks(orderId));
            assertThat(exception.getErrorCode())
                .isEqualTo(ProductErrorCode.STOCK_ALREADY_RESTORED);

            then(stock).should(never()).restore(anyInt(), anyLong());
        }

        @Test
        @DisplayName("주문 ID와 일치하는 기록이 존재하지 않으면 예외가 발생한다.")
        void rollbackStocks_fail_stockHistoryNotFound() {
            // given
            given(stockHistoryRepository.findDeductHistoriesByOrderId(anyLong()))
                .willReturn(List.of());

            // when & then
            AppException exception = assertThrows(AppException.class, () ->
                stockService.rollbackStocks(anyLong()));
            assertThat(exception.getErrorCode())
                .isEqualTo(ProductErrorCode.STOCK_HISTORY_NOT_FOUND);
        }

        @Test
        @DisplayName("복구 대상 재고가 존재하지 않으면 예외가 발생한다.")
        void rollbackStocks_fail_stockNotFound() {
            // given
            Long orderId = 100L;
            Long existingItemId = 1L;
            Long nonExistingItemId = 2L;

            StockDeductHistory history1 = new StockDeductHistory(existingItemId, 3);
            StockDeductHistory history2 = new StockDeductHistory(nonExistingItemId, 2);

            Stock stock = createMockStock(existingItemId);

            given(stockHistoryRepository.findDeductHistoriesByOrderId(orderId))
                .willReturn(List.of(history1, history2));
            given(stockRepository.findAllByProductItemIdsWithLock(anyList()))
                .willReturn(List.of(stock));


            // when & then
            AppException exception = assertThrows(AppException.class, () ->
                stockService.rollbackStocks(orderId));
            assertThat(exception.getErrorCode())
                .isEqualTo(ProductErrorCode.STOCK_NOT_FOUND);
            then(stockHistoryRepository).should(never())
                .existsRestoreHistoryByOrderId(anyLong());
            then(stock).should(never()).restore(anyInt(), anyLong());
        }

    }

}
