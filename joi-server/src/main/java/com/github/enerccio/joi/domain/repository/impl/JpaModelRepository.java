package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.joi.domain.repository.ModelRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

public class JpaModelRepository extends JpaExtendableRepository<Model> implements ModelRepository {

    @Override
    protected Class<Model> getEntityClass() {
        return Model.class;
    }

    @Override
    public java.util.List<com.github.enerccio.marginalia.domain.model.impl.Resource> findDeletedResources(
            Long ownerId, Long modelId) {
        return getEntityManager().createQuery("SELECT r FROM Resource r WHERE r.owner.id = :owner AND r.deleted = true"
                        + " AND r.clazz = :clazz AND r.objectId = :id", com.github.enerccio.marginalia.domain.model.impl.Resource.class)
                .setParameter("owner", ownerId)
                .setParameter("clazz", Model.class.getName())
                .setParameter("id", modelId)
                .getResultList();
    }

    @Override
    public boolean pathInUse(String path) {
        return getEntityManager().createQuery("SELECT COUNT(r) FROM Resource r WHERE r.path = :path AND r.deleted = false", Long.class)
                .setParameter("path", path)
                .getSingleResult() > 0;
    }
}
