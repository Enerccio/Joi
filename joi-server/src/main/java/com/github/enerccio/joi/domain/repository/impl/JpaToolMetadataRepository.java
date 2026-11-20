package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.ToolMetadata;
import com.github.enerccio.joi.domain.repository.ToolMetadataRepository;
import com.github.enerccio.joi.domain.tools.ToolScope;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;
import com.github.enerccio.marginalia.domain.security.model.User;
import jakarta.persistence.TypedQuery;

import java.util.List;

public class JpaToolMetadataRepository extends JpaExtendableRepository<ToolMetadata> implements ToolMetadataRepository {

    @Override
    protected Class<ToolMetadata> getEntityClass() {
        return ToolMetadata.class;
    }

    @Override
    public ToolMetadata find(ToolScope scope, Joi joi, User user, String toolKey) throws Exception {
        List<ToolMetadata> found = query(scope, joi, user, " AND t.toolKey = :key").setParameter("key", toolKey).getResultList();
        return found.isEmpty() ? null : found.getFirst();
    }

    @Override
    public List<ToolMetadata> findAll(ToolScope scope, Joi joi, User user) throws Exception {
        return query(scope, joi, user, "").getResultList();
    }

    private TypedQuery<ToolMetadata> query(ToolScope scope, Joi joi, User user, String more) {
        String where = switch (scope) {
            case JOI -> " AND t.joi = :joi";
            case USER -> " AND t.owner = :user";
            case APP -> "";
        };
        TypedQuery<ToolMetadata> query = entityManager.createQuery("SELECT t FROM ToolMetadata t WHERE t.deleted = false AND t.scope = :scope"
                + where + more + " ORDER BY t.id ASC", ToolMetadata.class).setParameter("scope", scope);
        switch (scope) {
            case JOI -> query.setParameter("joi", joi);
            case USER -> query.setParameter("user", user);
            case APP -> {
            }
        }
        return query;
    }
}
