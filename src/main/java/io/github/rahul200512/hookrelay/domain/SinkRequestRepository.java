package io.github.rahul200512.hookrelay.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SinkRequestRepository extends JpaRepository<SinkRequest, UUID> {

    List<SinkRequest> findTop50BySinkIdOrderByReceivedAtDesc(UUID sinkId);
}
