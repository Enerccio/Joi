package com.github.enerccio.joi.domain.repository.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.repository.SummaryRepository;
import com.github.enerccio.marginalia.domain.repository.impl.JpaExtendableRepository;

import java.util.Date;
import java.util.List;

public class JpaSummaryRepository extends JpaExtendableRepository<Summary> implements SummaryRepository {

    @Override
    protected Class<Summary> getEntityClass() {
        return Summary.class;
    }

    @Override
    public List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception {
        return entityManager.createQuery("SELECT s FROM Summary s WHERE s.deleted = false AND s.joi = :joi AND s.coverTo > :from AND s.coverFrom < :to ORDER BY s.coverFrom ASC, s.id ASC", Summary.class)
                .setParameter("joi", joi).setParameter("from", from).setParameter("to", to).getResultList();
    }
}
