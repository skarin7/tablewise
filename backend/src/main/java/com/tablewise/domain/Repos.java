package com.tablewise.domain;

import com.tablewise.domain.Catalog.*;
import com.tablewise.domain.Online.*;
import com.tablewise.domain.Sales.*;
import com.tablewise.domain.Tenancy.*;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repositories. Every finder that serves a request takes the outlet id. */
public final class Repos {
    private Repos() {}

    public interface OrganizationRepo extends JpaRepository<Organization, UUID> {}
    public interface OutletRepo extends JpaRepository<Outlet, UUID> {}
    public interface StaffRepo extends JpaRepository<Staff, UUID> {
        List<Staff> findByUserIdAndActiveTrue(UUID userId);
        List<Staff> findByEmailIgnoreCaseAndUserIdIsNullAndActiveTrue(String email);
        List<Staff> findByOutletIdOrderByFullName(UUID outletId);
        Optional<Staff> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface CounterRepo extends JpaRepository<Counter, UUID> {
        List<Counter> findByOutletIdOrderBySortOrderAscNameAsc(UUID outletId);
        Optional<Counter> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface TableRepo extends JpaRepository<DiningTable, UUID> {
        List<DiningTable> findByOutletIdAndActiveTrueOrderBySortOrderAscLabelAsc(UUID outletId);
        Optional<DiningTable> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface DeviceRepo extends JpaRepository<Device, UUID> {
        Optional<Device> findByTokenHash(byte[] hash);
        List<Device> findByOutletIdAndRevokedAtIsNull(UUID outletId);
        Optional<Device> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface PairingCodeRepo extends JpaRepository<PairingCode, UUID> {
        Optional<PairingCode> findFirstByDeviceKeyOrderByExpiresAtDesc(UUID deviceKey);
        Optional<PairingCode> findByCodeAndClaimedAtIsNull(String code);
        void deleteByClaimedAtIsNullAndExpiresAtBefore(java.time.Instant t);
    }
    public interface AuditRepo extends JpaRepository<AuditLog, Long> {
        List<AuditLog> findTop200ByOutletIdOrderByIdDesc(UUID outletId);
    }

    public interface CategoryRepo extends JpaRepository<Category, UUID> {
        List<Category> findByOutletIdOrderBySortOrderAscNameAsc(UUID outletId);
        Optional<Category> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface MenuItemRepo extends JpaRepository<MenuItem, UUID> {
        List<MenuItem> findByOutletIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(UUID outletId);
        Optional<MenuItem> findByIdAndOutletIdAndDeletedAtIsNull(UUID id, UUID outletId);
        List<MenuItem> findByOutletIdAndIdInAndDeletedAtIsNull(UUID outletId, Collection<UUID> ids);
        boolean existsByCategoryIdAndDeletedAtIsNull(UUID categoryId);
    }
    public interface SupplierRepo extends JpaRepository<Supplier, UUID> {
        Optional<Supplier> findByOutletIdAndNameIgnoreCase(UUID outletId, String name);
    }
    public interface IngredientRepo extends JpaRepository<Ingredient, UUID> {
        Optional<Ingredient> findByIdAndOutletIdAndDeletedAtIsNull(UUID id, UUID outletId);
        List<Ingredient> findByOutletIdAndIdIn(UUID outletId, Collection<UUID> ids);
    }
    public interface RecipeRepo extends JpaRepository<RecipeItem, RecipeItem.Pk> {
        List<RecipeItem> findByMenuItemId(UUID menuItemId);
        List<RecipeItem> findByMenuItemIdIn(Collection<UUID> ids);
        boolean existsByIngredientId(UUID ingredientId);
        void deleteByMenuItemId(UUID menuItemId);
    }
    public interface MovementRepo extends JpaRepository<StockMovement, Long> {}

    public interface CustomerRepo extends JpaRepository<Customer, UUID> {
        Optional<Customer> findByOutletIdAndPhone(UUID outletId, String phone);
    }
    public interface OrderRepo extends JpaRepository<PosOrder, UUID> {
        Optional<PosOrder> findByIdAndOutletId(UUID id, UUID outletId);

        @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        @org.springframework.data.jpa.repository.Query("select o from PosOrder o where o.id = :id and o.outletId = :outletId")
        Optional<PosOrder> lockByIdAndOutletId(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("outletId") UUID outletId);
    }
    public interface OrderItemRepo extends JpaRepository<OrderItem, UUID> {
        List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);
        List<OrderItem> findByOrderId(UUID orderId);
    }
    public interface StatusEventRepo extends JpaRepository<OrderStatusEvent, Long> {}
    public interface BillRepo extends JpaRepository<Bill, UUID> {
        Optional<Bill> findByOrderIdAndStatusNot(UUID orderId, String status);
        List<Bill> findByOrderIdInAndStatusNot(Collection<UUID> orderIds, String status);
    }
    public interface PaymentRepo extends JpaRepository<Payment, UUID> {
        List<Payment> findByBillId(UUID billId);
        Optional<Payment> findByBillIdAndStatus(UUID billId, String status);
        Optional<Payment> findByGatewayQrId(String qrId);
        Optional<Payment> findByGatewayPaymentId(String id);
        List<Payment> findByStatusAndMethod(String status, String method);
        Optional<Payment> findByIdAndOutletId(UUID id, UUID outletId);
    }
    public interface WebhookRepo extends JpaRepository<WebhookEvent, Long> {}

    public interface IntegrationRepo extends JpaRepository<Integration, UUID> {
        Optional<Integration> findByOutletIdAndPlatform(UUID outletId, String platform);
        Optional<Integration> findByPlatformAndExternalStoreId(String platform, String storeId);
        List<Integration> findByOutletId(UUID outletId);
    }
    public interface ItemMappingRepo extends JpaRepository<ItemMapping, UUID> {
        Optional<ItemMapping> findByIntegrationIdAndExternalItemId(UUID integrationId, String externalItemId);
    }
    public interface OnlineOrderRepo extends JpaRepository<OnlineOrder, UUID> {
        Optional<OnlineOrder> findByIdAndOutletId(UUID id, UUID outletId);
        Optional<OnlineOrder> findByIntegrationIdAndExternalOrderId(UUID integrationId, String externalOrderId);
    }
}
