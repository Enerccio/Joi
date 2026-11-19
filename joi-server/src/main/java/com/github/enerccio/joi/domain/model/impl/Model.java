package com.github.enerccio.joi.domain.model.impl;

import com.github.enerccio.joi.domain.collections.ConversionStatus;
import com.github.enerccio.joi.domain.collections.ModelFormat;
import com.github.enerccio.marginalia.domain.model.ExtendableEntity;
import com.github.enerccio.marginalia.domain.model.impl.Resource;
import com.github.enerccio.marginalia.domain.traits.CleanupReference;
import com.github.enerccio.marginalia.domain.traits.CleanupReference.Policy;
import com.github.enerccio.marginalia.domain.traits.ExtendedAttribute;
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
}
