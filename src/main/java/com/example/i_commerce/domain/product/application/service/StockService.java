package com.example.i_commerce.domain.product.application.service;


import com.example.i_commerce.domain.product.application.dto.StockDeductCommand;
import com.example.i_commerce.domain.product.entity.Stock;
import com.example.i_commerce.domain.product.event.StockDepletedEvent;
import com.example.i_commerce.domain.product.exception.ProductErrorCode;
import com.example.i_commerce.domain.product.repository.StockHistoryRepository;
import com.example.i_commerce.domain.product.repository.StockRepository;
import com.example.i_commerce.domain.product.repository.projection.StockDeductHistory;
import com.example.i_commerce.global.exception.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StockService {

    private final StockRepository stockRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public void deductStocks(List<StockDeductCommand> commands) {

        List<Long> productItemIds = commands.stream()
            .map(StockDeductCommand::productItemId)
            .sorted()
            .toList();

        Map<Long, Stock> stockMap = fetchStockMapWithLock(productItemIds);

        validateAllStocksFound(productItemIds, stockMap);

        if (stockHistoryRepository.existsDeductHistoryByOrderId(commands.getFirst().orderId())) {
            return;
        }

        List<Long> depletedItemIds = new ArrayList<>();

        for(StockDeductCommand command : commands) {
            Stock stock = stockMap.get(command.productItemId());
            stock.deduct(command.quantity(), command.orderId());

            if(stock.isOutOfStock()) {
                depletedItemIds.add(command.productItemId());
            }
        }

        if(!depletedItemIds.isEmpty()) {
            eventPublisher.publishEvent(new StockDepletedEvent(depletedItemIds));
        }

    }

    public void rollbackStocks(Long orderId) {

        List<StockDeductHistory> deductHistories = stockHistoryRepository
            .findDeductHistoriesByOrderId(orderId);

        if(deductHistories.isEmpty()) {
            throw new AppException(ProductErrorCode.STOCK_HISTORY_NOT_FOUND);
        }

        List<Long> productItemIds = deductHistories.stream()
            .map(StockDeductHistory::productItemId)
            .sorted()
            .toList();

        Map<Long, Stock> stockMap = fetchStockMapWithLock(productItemIds);

        validateAllStocksFound(productItemIds, stockMap);

        if(stockHistoryRepository.existsRestoreHistoryByOrderId(orderId)) {
            throw new AppException(ProductErrorCode.STOCK_ALREADY_RESTORED);
        }

        for (StockDeductHistory history : deductHistories) {
            Stock stock = stockMap.get(history.productItemId());
            stock.restore(history.changeQuantity(), orderId);
        }

    }


    private Map<Long, Stock> fetchStockMapWithLock(List<Long> sortedIds) {
        return stockRepository.findAllByProductItemIdsWithLock(sortedIds)
            .stream()
            .collect(Collectors.toMap(
                s -> s.getProductItem().getId(),
                Function.identity()
            ));
    }

    private void validateAllStocksFound(List<Long> productItemIds, Map<Long, Stock> foundIdsMap) {
        List<Long> missingIds = productItemIds.stream()
            .filter(id -> !foundIdsMap.containsKey(id))
            .toList();

        if(!missingIds.isEmpty()) {
            throw new AppException(ProductErrorCode.STOCK_NOT_FOUND);
        }
    }

}
