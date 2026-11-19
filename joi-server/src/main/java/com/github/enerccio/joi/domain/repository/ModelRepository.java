package com.github.enerccio.joi.domain.repository;

import com.github.enerccio.joi.domain.model.impl.Model;
import com.github.enerccio.marginalia.domain.repository.ExtendableRepository;

/** The 3D models of a user. */
public interface ModelRepository extends ExtendableRepository<Model> {

    /** The deleted resources of the owner linked to the model: older versions of its files. */
    java.util.List<com.github.enerccio.marginalia.domain.model.impl.Resource> findDeletedResources(
            Long ownerId, Long modelId);

    /** Whether a resource that is not deleted (of anyone) keeps its file at the path. */
    boolean pathInUse(String path);
}
