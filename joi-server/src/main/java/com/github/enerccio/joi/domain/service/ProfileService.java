package com.github.enerccio.joi.domain.service;

import com.github.enerccio.joi.domain.bodies.Profiles;
import com.github.enerccio.joi.domain.model.impl.Joi;

/**
 * The profiles of an assistant (home, work; {@link Profiles}): which body she is in and how its parts are in each, and
 * which node is in which. The default profile is the state the bodies keep themselves.
 */
public interface ProfileService {

    /** Her profiles as stored now. */
    Profiles profiles(Joi joi) throws Exception;

    /** Adds a profile that starts as the default one is; returns its id. Throws when the name is blank or taken. */
    String create(Joi joi, String name) throws Exception;

    void rename(Joi joi, String profile, String name) throws Exception;

    /** Removes a profile (not the default one); its nodes are in the default one again. */
    void delete(Joi joi, String profile) throws Exception;

    /** The node (its stable id) is in the profile; the default one for none. */
    void assign(Joi joi, String nodeId, String profile) throws Exception;

    /** A node connected: remembered by its name, for the user to assign it to a profile. */
    void seen(Joi joi, String nodeId, String name) throws Exception;

    /** Forgets a node that is not connected: its name and its assignment. */
    void forget(Joi joi, String nodeId) throws Exception;

    /** The profile the node is in: the default one when it is assigned to none. */
    String profileOf(Joi joi, String nodeId) throws Exception;

    /** The profile of her last turn or change. */
    String last(Joi joi) throws Exception;

    /** Remembers the profile as the last one; returns whether it was another one before. */
    boolean used(Joi joi, String profile) throws Exception;
}
