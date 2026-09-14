package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByDeliveryIdOrderByAttemptNoAsc(UUID deliveryId);
}
