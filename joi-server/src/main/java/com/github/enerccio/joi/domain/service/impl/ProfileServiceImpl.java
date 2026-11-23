package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.model.impl.Body;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.service.BodyService;
import com.github.enerccio.joi.domain.service.JoiService;
import com.github.enerccio.joi.domain.service.ProfileService;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps the profiles in an extended attribute of the assistant. */
public class ProfileServiceImpl implements ProfileService {

    @Autowired
    private JoiService joiService;

    @Autowired
    @Lazy
    private BodyService bodyService;

    @Override
    @CommonTxReadOnly
    public Profiles profiles(Joi joi) throws Exception {
        Joi stored = joiService.find(joi.getId());
        return stored == null ? new Profiles() : stored.profileSet();
    }

    @Override
    @CommonTx
    public String create(Joi joi, String name) throws Exception {
        String named = StringUtils.strip(name);
        Profiles set = profiles(joi);
        if (StringUtils.isBlank(named)) {
            throw new IllegalArgumentException("A profile needs a name");
        }
        if (set.nameTaken(named, null)) {
            throw new IllegalArgumentException("She has a profile called " + named + " already");
        }
        Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
        Body active = null;
        for (Body body : bodyService.findForJoi(joi)) {
            defaults.put(body.getUuid(), body.partStateMap());
            if (body.isActive()) {
                active = body;
            }
        }
        String id = set.add(named, active == null ? null : active.getUuid(), defaults);
        joiService.update(joi, stored -> stored.setProfileSet(set));
        return id;
    }

    @Override
    @CommonTx
    public void rename(Joi joi, String profile, String name) throws Exception {
        String named = StringUtils.strip(name);
        Profiles set = profiles(joi);
        if (StringUtils.isBlank(named) && !Profiles.isDefault(profile) || set.nameTaken(named, profile)) {
            throw new IllegalArgumentException(StringUtils.isBlank(named) ? "A profile needs a name"
                    : "She has a profile called " + named + " already");
        }
        set.rename(profile, named);
        joiService.update(joi, stored -> stored.setProfileSet(set));
    }

    @Override
    @CommonTx
    public void delete(Joi joi, String profile) throws Exception {
        Profiles set = profiles(joi);
        set.remove(profile);
        joiService.update(joi, stored -> stored.setProfileSet(set));
    }

    @Override
    @CommonTx
    public void assign(Joi joi, String nodeId, String profile) throws Exception {
        Profiles set = profiles(joi);
        set.assign(nodeId, profile);
        joiService.update(joi, stored -> stored.setProfileSet(set));
    }

    @Override
    @CommonTx
    public void seen(Joi joi, String nodeId, String name) throws Exception {
        Profiles set = profiles(joi);
        if (set.seen(nodeId, name)) {
            joiService.update(joi, stored -> stored.setProfileSet(set));
        }
    }

    @Override
    @CommonTx
    public void forget(Joi joi, String nodeId) throws Exception {
        Profiles set = profiles(joi);
        set.forget(nodeId);
        joiService.update(joi, stored -> stored.setProfileSet(set));
    }

    @Override
    @CommonTxReadOnly
    public String profileOf(Joi joi, String nodeId) throws Exception {
        return profiles(joi).profileOf(nodeId);
    }

    @Override
    @CommonTxReadOnly
    public String last(Joi joi) throws Exception {
        return profiles(joi).last();
    }

    @Override
    @CommonTx
    public boolean used(Joi joi, String profile) throws Exception {
        Profiles set = profiles(joi);
        if (!set.used(profile)) {
            return false;
        }
        joiService.update(joi, stored -> stored.setProfileSet(set));
        return true;
    }
}
