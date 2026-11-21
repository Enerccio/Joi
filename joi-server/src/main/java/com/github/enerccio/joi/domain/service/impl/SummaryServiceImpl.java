package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.memory.SummaryCoverage;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Summary;
import com.github.enerccio.joi.domain.repository.SummaryRepository;
import com.github.enerccio.joi.domain.service.SummaryService;
import com.github.enerccio.marginalia.domain.model.impl.AI;
import com.github.enerccio.marginalia.domain.model.impl.GpuLock;
import com.github.enerccio.marginalia.domain.repository.GpuLockRepository;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.AIService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SummaryServiceImpl extends ExtendableServiceImpl<Summary, SummaryRepository> implements SummaryService {

    /** The end of time for a query that reads everything. */
    private static final Date FAR = new Date(Long.MAX_VALUE / 4);
    private static final Date EPOCH = new Date(0);

    @Autowired
    private GpuLockRepository gpuLocks;
    @Autowired
    private AIService aiService;
    @Autowired
    private User currentUser;

    @Override
    @CommonTxReadOnly
    public List<Summary> findOverlapping(Joi joi, Date from, Date to) throws Exception {
        return getRepository().findOverlapping(joi, from, to);
    }

    @Override
    @CommonTxReadOnly
    public List<Summary> findAllOf(Joi joi) throws Exception {
        return getRepository().findOverlapping(joi, EPOCH, FAR);
    }

    @Override
    @CommonTxReadOnly
    public List<Summary> findForPrompt(Joi joi, Date from, Date to) throws Exception {
        return SummaryCoverage.uncovered(getRepository().findOverlapping(joi, from, to));
    }

    @Override
    @CommonTxReadOnly
    public Lane lane(AI ai) throws Exception {
        String lockUuid = ai.getGpuLock();
        if (lockUuid != null) {
            List<GpuLock> locks = gpuLocks.findAll().stream().filter(l -> l.getGpu() != null && !l.getGpu().isDeleted()).toList();
            GpuLock lock = locks.stream().filter(l -> lockUuid.equals(l.getUuid())).findFirst().orElse(null);
            if (lock != null) {
                String gpu = lock.getGpu().getUuid();
                Set<String> onGpu = locks.stream().filter(l -> gpu.equals(l.getGpu().getUuid())).map(GpuLock::getUuid).collect(Collectors.toSet());
                // every model on the GPU, whoever's it is: they all wait for the same card
                int limit = aiService.findAll().stream().filter(a -> a.getGpuLock() != null && onGpu.contains(a.getGpuLock()))
                        .mapToInt(AI::getConnections).min().orElse(ai.getConnections());
                int locked = locks.stream().filter(l -> gpu.equals(l.getGpu().getUuid())).mapToInt(GpuLock::getMaxConcurrent)
                        .filter(n -> n > 0).min().orElse(0);
                return new Lane("gpu:" + gpu, Math.max(1, locked > 0 ? Math.min(limit, locked) : limit));
            }
        }
        return new Lane("ai:" + currentUser.getId() + ":" + ai.getId(), Math.max(1, ai.getConnections()));
    }
}
