package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.bodies.ModelCapabilities;
import com.github.enerccio.joi.domain.bodies.ToggleGroup;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.joi.domain.collections.ProposalStatus;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 3D model of a user, the file a body is made of (see {@code DESIGN.md} → Bodies): a VRM, a glTF or an FBX, or a
 * zip of one with the files it needs. The file is a platform {@link Resource} of the user (for now as uploaded; the
 * converted VRM comes with the import step). A body will point to its models (the full one, a lighter one for the
 * Looking Glass).
 * <p>
 * <p>
 * An FBX (alone, in a zip or in a Unity package) is <b>converted</b> to a VRM on the server
 * ({@code ModelConversionService}, Blender): the result is a second resource of the model, with the report of what
 * the conversion found and did. The user chooses which FBX is the avatar and which meshes to leave out.
 * <p>
 * How she looks in it (see {@code DESIGN.md} → Bodies, self-image) belongs to the model too: the snapshots the renderer
 * takes (the whole model and a close-up of each toggle described apart, shown and hidden), the user's notes and images,
 * the description of what never changes and a sentence per state of each described toggle, proposed by a multimodal
 * model and reviewed. A body puts them together for the state it is in ({@code Appearance}).
 * <p>
 * The resource is the only column (the reference); the rest is extended attributes.
 */
@Entity
@Table(name = "models")
public class Model extends ExtendableEntity {

    /** The uploaded file, null until there is one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id")
    @CleanupReference(Policy.OWNS)
    private Resource resource;

    @ExtendedAttribute
    @Transient
    private String name;

    /** The format of the model, of the main file in a zip. */
    @ExtendedAttribute
    @Transient
    private ModelFormat format;

    /** The path of the model file inside the zip, null when the upload was the model file itself. */
    @ExtendedAttribute
    @Transient
    private String mainFile;

    /** How many files the upload had: 1, or the files of the zip. */
    @ExtendedAttribute
    @Transient
    private Integer fileCount;

    /** The name of the uploaded file (a copy of the resource's, so a list needs no resource). */
    @ExtendedAttribute
    @Transient
    private String fileName;

    /** The size of the uploaded file in bytes. */
    @ExtendedAttribute
    @Transient
    private Long fileSize;

    /** The FBX files of the upload (paths in the zip or the package), the likely avatar first. */
    @ExtendedAttribute(inject = true, injectPrefix = "fbxFiles")
    @Transient
    private List<String> fbxFiles = new ArrayList<>();

    /** The FBX the conversion reads, null for the first of {@link #fbxFiles}. */
    @ExtendedAttribute
    @Transient
    private String convertFbx;

    /** The meshes the conversion leaves out (props, alternative outfits). */
    @ExtendedAttribute(inject = true, injectPrefix = "removeMeshes")
    @Transient
    private List<String> removeMeshes = new ArrayList<>();

    /** Shape keys the conversion deletes (on any mesh): names or parts of names, any case. */
    @ExtendedAttribute(inject = true, injectPrefix = "dropShapeKeys")
    @Transient
    private List<String> dropShapeKeys = new ArrayList<>();

    /** Shape keys the conversion makes part of the mesh (always on), as {@code mesh / key}. */
    @ExtendedAttribute(inject = true, injectPrefix = "bakeShapeKeys")
    @Transient
    private List<String> bakeShapeKeys = new ArrayList<>();

    /** The largest texture side of the converted model, null for the default (2048). */
    @ExtendedAttribute
    @Transient
    private Integer maxTextureSize;

    /** The converted VRM, a resource of the user; null until a conversion succeeded. */
    @ExtendedAttribute
    @Transient
    @CleanupReference(value = Policy.OWNS, target = Resource.class)
    private Long convertedResourceId;

    @ExtendedAttribute
    @Transient
    private Long convertedFileSize;

    @ExtendedAttribute
    @Transient
    private ConversionStatus conversionStatus;

    @ExtendedAttribute
    @Transient
    private Date convertedAt;

    /** Why the last conversion failed, null when it did not. */
    @ExtendedAttribute
    @Transient
    private String conversionError;

    /** What the last conversion found and did (JSON, the converter's report: meshes, materials, expressions...). */
    @ExtendedAttribute
    @Transient
    private String conversionReport;

    /** The end of the converter's output of the last conversion. */
    @ExtendedAttribute
    @Transient
    private String conversionLog;

    /** What the model can do (JSON of {@code ModelCapabilities}), read from its VRM or GLB; null until read. */
    @ExtendedAttribute
    @Transient
    private String capabilities;

    /** The names the user gave the capabilities, by their id ({@code mesh:Scarf} → {@code scarf}). */
    @ExtendedAttribute(inject = true, injectPrefix = "capabilityNames")
    @Transient
    private Map<String, String> capabilityNames = new LinkedHashMap<>();

    /** What the user knows of the model (what a toggle is, where it is from), for the description. */
    @ExtendedAttribute
    @Transient
    private String notes;

    /** The snapshots the renderer took: the shot ({@code full-front}, {@code part:<toggle id>:shown}) → the resource's id. */
    @ExtendedAttribute(inject = true, injectPrefix = "snapshots")
    @Transient
    private Map<String, String> snapshots = new LinkedHashMap<>();

    /** The hash of the VRM the snapshots are of. */
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

    /** How she looks in it, the parts that never change (the toggles are described apart), in the second person. */
    @ExtendedAttribute
    @Transient
    private String appearance;

    /** How a described toggle looks shown: the toggle's id → a sentence. */
    @ExtendedAttribute(inject = true, injectPrefix = "partShown")
    @Transient
    private Map<String, String> partShown = new LinkedHashMap<>();

    /** How it looks hidden (what is there instead): the toggle's id → a sentence. */
    @ExtendedAttribute(inject = true, injectPrefix = "partHidden")
    @Transient
    private Map<String, String> partHidden = new LinkedHashMap<>();

    /** The toggles described apart, when the user chose them ({@link #describeTogglesChosen}). */
    @ExtendedAttribute(inject = true, injectPrefix = "describeToggles")
    @Transient
    private List<String> describeToggles = new ArrayList<>();

    /** Whether the user chose the toggles to describe; else the shape key toggles are. */
    @ExtendedAttribute
    @Transient
    private Boolean describeTogglesChosen;

    /** The hash of the VRM the description was made from, null when it was not proposed from snapshots. */
    @ExtendedAttribute
    @Transient
    private String describedHash;

    /** The LLM's last proposal to review (JSON: {@code AppearanceProposal}), null for none. */
    @ExtendedAttribute
    @Transient
    private String proposal;

    @ExtendedAttribute
    @Transient
    private ProposalStatus proposalStatus;

    @ExtendedAttribute
    @Transient
    private String proposalError;

    /** How far the running request is ("3 / 16"). */
    @ExtendedAttribute
    @Transient
    private String proposalProgress;

    /**
     * Which toggles are shown by default, the user's choice (JSON: the toggle's id → shown); a toggle not in it is as the
     * model comes. The snapshots are taken in it and a new body comes in it.
     */
    @ExtendedAttribute
    @Transient
    private String defaultState;

    /** The user's groups of toggles (JSON: {@code ToggleGroup}s), described as a whole. */
    @ExtendedAttribute
    @Transient
    private String toggleGroups;

    /** The sentences of the groups' states: {@code <group id>:<state id>} → the sentence. */
    @ExtendedAttribute(inject = true, injectPrefix = "groupSentences")
    @Transient
    private Map<String, String> groupSentences = new LinkedHashMap<>();

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public Map<String, String> getCapabilityNames() {
        return capabilityNames;
    }

    public void setCapabilityNames(Map<String, String> capabilityNames) {
        this.capabilityNames = capabilityNames == null ? new LinkedHashMap<>() : new LinkedHashMap<>(capabilityNames);
    }

    public List<String> getFbxFiles() {
        return fbxFiles;
    }

    public void setFbxFiles(List<String> fbxFiles) {
        this.fbxFiles = fbxFiles == null ? new ArrayList<>() : new ArrayList<>(fbxFiles);
    }

    public String getConvertFbx() {
        return convertFbx;
    }

    public void setConvertFbx(String convertFbx) {
        this.convertFbx = convertFbx;
    }

    /**
     * Whether the model has a VRM the renderer shows: a converted one, or an upload that is a .vrm or a .glb itself.
     * Only such a model can be a body's.
     */
    public boolean renderable() {
        return convertedResourceId != null || (resource != null && mainFile == null
                && (format == ModelFormat.VRM || format == ModelFormat.GLB));
    }

    /** The FBX a conversion reads: the chosen one, else the likely avatar; null when the upload has none. */
    public String fbxToConvert() {
        if (convertFbx != null && fbxFiles.contains(convertFbx)) {
            return convertFbx;
        }
        return fbxFiles.isEmpty() ? null : fbxFiles.getFirst();
    }

    public List<String> getRemoveMeshes() {
        return removeMeshes;
    }

    public void setRemoveMeshes(List<String> removeMeshes) {
        this.removeMeshes = removeMeshes == null ? new ArrayList<>() : new ArrayList<>(removeMeshes);
    }

    public List<String> getDropShapeKeys() {
        return dropShapeKeys;
    }

    public void setDropShapeKeys(List<String> dropShapeKeys) {
        this.dropShapeKeys = dropShapeKeys == null ? new ArrayList<>() : new ArrayList<>(dropShapeKeys);
    }

    public List<String> getBakeShapeKeys() {
        return bakeShapeKeys;
    }

    public void setBakeShapeKeys(List<String> bakeShapeKeys) {
        this.bakeShapeKeys = bakeShapeKeys == null ? new ArrayList<>() : new ArrayList<>(bakeShapeKeys);
    }

    public Integer getMaxTextureSize() {
        return maxTextureSize;
    }

    public void setMaxTextureSize(Integer maxTextureSize) {
        this.maxTextureSize = maxTextureSize;
    }

    public Long getConvertedResourceId() {
        return convertedResourceId;
    }

    public void setConvertedResourceId(Long convertedResourceId) {
        this.convertedResourceId = convertedResourceId;
    }

    public Long getConvertedFileSize() {
        return convertedFileSize;
    }

    public void setConvertedFileSize(Long convertedFileSize) {
        this.convertedFileSize = convertedFileSize;
    }

    public ConversionStatus getConversionStatus() {
        return conversionStatus;
    }

    public void setConversionStatus(ConversionStatus conversionStatus) {
        this.conversionStatus = conversionStatus;
    }

    public Date getConvertedAt() {
        return convertedAt;
    }

    public void setConvertedAt(Date convertedAt) {
        this.convertedAt = convertedAt;
    }

    public String getConversionError() {
        return conversionError;
    }

    public void setConversionError(String conversionError) {
        this.conversionError = conversionError;
    }

    public String getConversionReport() {
        return conversionReport;
    }

    public void setConversionReport(String conversionReport) {
        this.conversionReport = conversionReport;
    }

    public String getConversionLog() {
        return conversionLog;
    }

    public void setConversionLog(String conversionLog) {
        this.conversionLog = conversionLog;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ModelFormat getFormat() {
        return format;
    }

    public void setFormat(ModelFormat format) {
        this.format = format;
    }

    public String getMainFile() {
        return mainFile;
    }

    public void setMainFile(String mainFile) {
        this.mainFile = mainFile;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public void setFileCount(Integer fileCount) {
        this.fileCount = fileCount;
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

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public Map<String, String> getPartShown() {
        return partShown;
    }

    public void setPartShown(Map<String, String> partShown) {
        this.partShown = partShown;
    }

    public Map<String, String> getPartHidden() {
        return partHidden;
    }

    public void setPartHidden(Map<String, String> partHidden) {
        this.partHidden = partHidden;
    }

    public List<String> getDescribeToggles() {
        return describeToggles;
    }

    public void setDescribeToggles(List<String> describeToggles) {
        this.describeToggles = describeToggles;
    }

    public Boolean getDescribeTogglesChosen() {
        return describeTogglesChosen;
    }

    public void setDescribeTogglesChosen(Boolean describeTogglesChosen) {
        this.describeTogglesChosen = describeTogglesChosen;
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

    public String getProposalProgress() {
        return proposalProgress;
    }

    public void setProposalProgress(String proposalProgress) {
        this.proposalProgress = proposalProgress;
    }

    public String getDefaultState() {
        return defaultState;
    }

    public void setDefaultState(String defaultState) {
        this.defaultState = defaultState;
    }

    public String getToggleGroups() {
        return toggleGroups;
    }

    public void setToggleGroups(String toggleGroups) {
        this.toggleGroups = toggleGroups;
    }

    public Map<String, String> getGroupSentences() {
        return groupSentences;
    }

    public void setGroupSentences(Map<String, String> groupSentences) {
        this.groupSentences = groupSentences;
    }

    /** The user's groups of toggles. */
    public List<ToggleGroup> toggleGroups() {
        return ToggleGroup.listFromJson(toggleGroups);
    }

    /** The toggle's id → whether it is shown by default, as the user chose. */
    public Map<String, Boolean> defaultStateMap() {
        if (defaultState == null || defaultState.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Boolean> read = new Gson().fromJson(defaultState, new TypeToken<LinkedHashMap<String, Boolean>>() {
            }.getType());
            return read == null ? new LinkedHashMap<>() : read;
        } catch (JsonParseException e) {
            return new LinkedHashMap<>();
        }
    }

    public void setDefaultStateMap(Map<String, Boolean> state) {
        this.defaultState = state == null || state.isEmpty() ? null : new Gson().toJson(state);
    }

    /**
     * Whether the toggle is shown by default: as the default state of its group says, else as the user chose on the
     * toggle, else as the model comes.
     */
    public boolean shownByDefault(ModelCapabilities.Toggle toggle) {
        Boolean byGroup = groupDefault(toggleGroups(), toggle.id());
        return byGroup != null ? byGroup : defaultStateMap().getOrDefault(toggle.id(), toggle.shown());
    }

    /** Every toggle's id → whether it is shown by default. */
    public Map<String, Boolean> defaultShown(ModelCapabilities capabilities) {
        Map<String, Boolean> chosen = defaultStateMap();
        List<ToggleGroup> groups = toggleGroups();
        Map<String, Boolean> state = new LinkedHashMap<>();
        for (ModelCapabilities.Toggle toggle : capabilities == null ? List.<ModelCapabilities.Toggle>of() : capabilities.toggles()) {
            Boolean byGroup = groupDefault(groups, toggle.id());
            state.put(toggle.id(), byGroup != null ? byGroup : chosen.getOrDefault(toggle.id(), toggle.shown()));
        }
        return state;
    }

    /** What the default state of the toggle's group says of it; null when it is in no group or the group has no default. */
    private static Boolean groupDefault(List<ToggleGroup> groups, String toggleId) {
        for (ToggleGroup group : groups) {
            if (group.toggles().contains(toggleId)) {
                ToggleGroup.State state = group.defaultStateOf();
                return state == null ? null : state.shown().get(toggleId);
            }
        }
        return null;
    }

    /** What the toggle is called: the user's name, else its default name. */
    public String toggleName(ModelCapabilities.Toggle toggle) {
        String name = capabilityNames.get(toggle.id());
        return name == null || name.isBlank() ? toggle.defaultName() : name;
    }

    /**
     * The toggles described apart (each with a sentence for shown and one for hidden): the ones the user chose, else
     * the shape key toggles ("Hide Halo", "Visor Off"), which are made to be switched.
     */
    public List<String> describedToggles(ModelCapabilities capabilities) {
        if (capabilities == null) {
            return List.of();
        }
        List<String> ids = capabilities.toggles().stream().map(ModelCapabilities.Toggle::id).toList();
        if (Boolean.TRUE.equals(describeTogglesChosen)) {
            return ids.stream().filter(describeToggles::contains).toList();
        }
        return capabilities.toggles().stream().filter(toggle -> toggle.key() != null).map(ModelCapabilities.Toggle::id).toList();
    }
}
