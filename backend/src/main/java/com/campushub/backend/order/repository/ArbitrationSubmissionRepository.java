package com.campushub.backend.order.repository;

import com.campushub.backend.order.domain.ArbitrationSubmission;

import java.util.List;

public interface ArbitrationSubmissionRepository {

    ArbitrationSubmission save(ArbitrationSubmission submission);

    List<ArbitrationSubmission> findByOrderId(Long orderId);

    List<ArbitrationSubmission> findByOrderIdAndRole(Long orderId, String role);

    int countByOrderId(Long orderId);
}
