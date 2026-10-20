package com.bytedesk.ai.statistic;

import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTokenUsageRepository
        extends JpaRepository<AiTokenUsageEntity, Long>, JpaSpecificationExecutor<AiTokenUsageEntity> {

    @Query("SELECT new com.bytedesk.ai.statistic.AiTokenUsageTotals("
            + "COUNT(u), SUM(u.promptTokens), SUM(u.completionTokens), SUM(u.totalTokens), "
            + "SUM(u.totalCostUsd), SUM(u.totalCostCny), "
            + "COUNT(CASE WHEN u.pricingAvailable = true THEN u.id ELSE NULL END)) "
            + "FROM bytedesk_ai_token_usage u "
            + "WHERE u.orgUid = :orgUid AND u.deleted = false")
    AiTokenUsageTotals summarizeAll(@Param("orgUid") String orgUid);

    @Query("SELECT new com.bytedesk.ai.statistic.AiTokenUsageTotals("
            + "COUNT(u), SUM(u.promptTokens), SUM(u.completionTokens), SUM(u.totalTokens), "
            + "SUM(u.totalCostUsd), SUM(u.totalCostCny), "
            + "COUNT(CASE WHEN u.pricingAvailable = true THEN u.id ELSE NULL END)) "
            + "FROM bytedesk_ai_token_usage u "
            + "WHERE u.orgUid = :orgUid AND u.deleted = false "
            + "AND u.createdAt >= :start AND u.createdAt < :end")
    AiTokenUsageTotals summarizePeriod(@Param("orgUid") String orgUid,
            @Param("start") ZonedDateTime start, @Param("end") ZonedDateTime end);

    List<AiTokenUsageEntity> findByOrgUidAndCreatedAtBetweenAndDeletedFalseOrderByCreatedAtAsc(
            String orgUid, ZonedDateTime start, ZonedDateTime end);

    @Query("SELECT u.provider, u.model, COUNT(u), SUM(u.promptTokens), "
            + "SUM(u.completionTokens), SUM(u.totalTokens), SUM(u.totalCostUsd), "
            + "SUM(u.totalCostCny) "
            + "FROM bytedesk_ai_token_usage u "
            + "WHERE u.orgUid = :orgUid AND u.deleted = false "
            + "GROUP BY u.provider, u.model "
            + "ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> groupByModel(@Param("orgUid") String orgUid);
}
