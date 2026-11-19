package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A body of an assistant (see {@code DESIGN.md} → Bodies): what she looks like on a display. It is made of the user's
 * 3D {@link Model models}, the full one and, optionally, a lighter one for the Looking Glass, and has a name and an
 * appearance description (how she looks, in her prompt). What it can do (expressions, toggles) is the model's.
 * <p>
 * One body of an assistant is {@link #isActive() active}: the one she is in. Switching is an event for her.
 * <p>
 * Her <b>self-image</b>: the renderer takes snapshots of the model, the user adds notes and images of their own, and a
 * multimodal LLM proposes the description and the names of the toggles, which the user reviews before they are used.
 * The description is tied to the hash of the model it was made from, so a changed model asks for a new one.
 */
@Entity
@Table(name = "bodies")
public class Body extends ExtendableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "joi_id", nullable = false)
    @CleanupReference(Policy.OWNED_BY)
    private Joi joi;

    /** The full model, null until one is chosen. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    @CleanupReference(Policy.WEAK)
    private Model model;

    /** The lighter model for the Looking Glass, null for the full one there too. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lkg_model_id")
    @CleanupReference(Policy.WEAK)
    private Model lookingGlassModel;

    /** Whether she is in this body now (one of hers at most). */
    @Column(nullable = false)
    private boolean active;

    @ExtendedAttribute
    @Transient
    private String name;

    /** How she looks in this body, in her prompt: written by the user (later proposed by the LLM from snapshots). */
    @ExtendedAttribute
    @Transient
    private String description;

    /** What the user knows of the body (what a toggle is, where it is from), for the LLM's proposal. */
    @ExtendedAttribute
    @Transient
    private String notes;

    /** The snapshots of the full model the renderer took: the shot (e.g. {@code full-front}) → the resource's id. */
    @ExtendedAttribute(inject = true, injectPrefix = "snapshots")
    @Transient
    private Map<String, String> snapshots = new LinkedHashMap<>();

    /** The hash of the model's file the snapshots are of. */
    @ExtendedAttribute
    @Transient
    private String snapshotsHash;

    @ExtendedAttribute
    @Transient
    private Date snapshotsAt;

    /** The images the user gave (store page, in-world photos): the resource's id → the file's name. */
    @ExtendedAttribute(inject = true, injectPrefix = "references")
    @Transient
    private Map<String, String> references = new LinkedHashMap<>();

    /** The hash of the model's file the description was made for, null when it was not proposed from snapshots. */
    @ExtendedAttribute
    @Transient
    private String describedHash;

    /** The LLM's last proposal to review (JSON: {@code BodyProposal}), null for none. */
    @ExtendedAttribute
    @Transient
    private String proposal;

    @ExtendedAttribute
    @Transient
    private ProposalStatus proposalStatus;

    @ExtendedAttribute
    @Transient
    private String proposalError;

    public Joi getJoi() {
        return joi;
    }

    public void setJoi(Joi joi) {
        this.joi = joi;
    }

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Model getLookingGlassModel() {
        return lookingGlassModel;
    }

    public void setLookingGlassModel(Model lookingGlassModel) {
        this.lookingGlassModel = lookingGlassModel;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Map<String, String> getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(Map<String, String> snapshots) {
        this.snapshots = snapshots;
    }

    public String getSnapshotsHash() {
        return snapshotsHash;
    }

    public void setSnapshotsHash(String snapshotsHash) {
        this.snapshotsHash = snapshotsHash;
    }

    public Date getSnapshotsAt() {
        return snapshotsAt;
    }

    public void setSnapshotsAt(Date snapshotsAt) {
        this.snapshotsAt = snapshotsAt;
    }

    public Map<String, String> getReferences() {
        return references;
    }

    public void setReferences(Map<String, String> references) {
        this.references = references;
    }

    public String getDescribedHash() {
        return describedHash;
    }

    public void setDescribedHash(String describedHash) {
        this.describedHash = describedHash;
    }

    public String getProposal() {
        return proposal;
    }

    public void setProposal(String proposal) {
        this.proposal = proposal;
    }

    public ProposalStatus getProposalStatus() {
        return proposalStatus;
    }

    public void setProposalStatus(ProposalStatus proposalStatus) {
        this.proposalStatus = proposalStatus;
    }

    public String getProposalError() {
        return proposalError;
    }

    public void setProposalError(String proposalError) {
        this.proposalError = proposalError;
    }
}
