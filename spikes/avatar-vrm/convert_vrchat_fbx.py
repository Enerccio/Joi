"""
Spike: converts the FBX of a VRChat avatar into a VRM 1.0 model, headless in Blender with the "VRM format" add-on
(extensions.blender.org, id vrm).

    Blender -b --factory-startup --python convert_vrchat_fbx.py -- <config.json>

The config names the FBX, the materials (Unity material -> textures, read from the .mat files of the package), the meshes
to leave out, the shape keys to bake into the mesh or drop, and the expressions to map. It is per avatar: a VRChat
package has no standard way to say which toggles are on by default or which keys are mood expressions.

What it does not carry over: the Poiyomi look (only the color, normal and emission textures go into MToon), PhysBones
(spring bones are guessed by the add-on), VRCFury, contacts, particles and animator states.
"""
import json
import re
import sys

import addon_utils
import bpy

ADDON = "bl_ext.blender_org.vrm"


def log(*args):
    print("[convert]", *args, flush=True)


def load_config():
    path = sys.argv[sys.argv.index("--") + 1]
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def import_fbx(path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    addon_utils.enable(ADDON, default_set=True)
    bpy.ops.import_scene.fbx(filepath=path)
    armatures = [o for o in bpy.data.objects if o.type == "ARMATURE"]
    if len(armatures) != 1:
        raise RuntimeError("expected one armature, found %d" % len(armatures))
    return armatures[0]


def remove_meshes(names):
    for name in names:
        obj = bpy.data.objects.get(name)
        if obj is not None:
            bpy.data.objects.remove(obj, do_unlink=True)
            log("removed mesh", name)


def bake_shape_key(obj, key_name):
    """
    Makes the shape key part of the mesh: the basis moves by the key's offset and every other key keeps its own offset on
    top of the new basis. Used for keys like "hide this part" that must always be on.
    """
    keys = obj.data.shape_keys.key_blocks
    key = keys.get(key_name)
    if key is None:
        return
    basis = keys[0]
    delta = [key.data[i].co - basis.data[i].co for i in range(len(basis.data))]
    for kb in keys:
        if kb == key:
            continue
        for i, d in enumerate(delta):
            kb.data[i].co += d
    for i, v in enumerate(obj.data.vertices):
        v.co = basis.data[i].co
    obj.shape_key_remove(key)
    log("baked", obj.name, key_name)


def clean_shape_keys(cfg):
    drop = [re.compile(p, re.IGNORECASE) for p in cfg.get("dropShapeKeys", [])]
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.data.shape_keys is None:
            continue
        for key_name in cfg.get("bakeShapeKeys", {}).get(obj.name, []):
            bake_shape_key(obj, key_name)
        removed = 0
        for kb in list(obj.data.shape_keys.key_blocks)[1:]:
            # the separators ("Eye -------") carry nothing
            if any(p.search(kb.name) for p in drop) or re.fullmatch(r"[^A-Za-z0-9]*|.*[-—]{5,}.*", kb.name):
                obj.shape_key_remove(kb)
                removed += 1
        if removed:
            log("dropped", removed, "shape keys of", obj.name)


def setup_material(mat, spec, tex_dir):
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = next(n for n in nodes if n.type == "BSDF_PRINCIPLED")

    def image_node(rel, non_color=False):
        img = bpy.data.images.load(tex_dir + "/" + rel, check_existing=True)
        if non_color:
            img.colorspace_settings.name = "Non-Color"
        node = nodes.new("ShaderNodeTexImage")
        node.image = img
        return node

    textures = spec.get("textures", {})
    # the FBX import leaves an emission color on some materials; without an emission map MToon makes them glow white
    bsdf.inputs["Emission Strength"].default_value = 0.0
    bsdf.inputs["Emission Color"].default_value = (0, 0, 0, 1)
    base_image = None
    if "_MainTex" in textures:
        tex = image_node(textures["_MainTex"])
        base_image = tex.image
        links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        if spec["floats"].get("_Mode", 0) in (1.0, 3.0):
            links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    if "_BumpMap" in textures:
        tex = image_node(textures["_BumpMap"], non_color=True)
        normal = nodes.new("ShaderNodeNormalMap")
        links.new(tex.outputs["Color"], normal.inputs["Color"])
        links.new(normal.outputs["Normal"], bsdf.inputs["Normal"])
    if spec["floats"].get("_EnableEmission") and "_EmissionMap" in textures:
        tex = image_node(textures["_EmissionMap"])
        links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
        bsdf.inputs["Emission Strength"].default_value = 1.0

    bpy.ops.vrm.convert_material_to_mtoon1(material_name=mat.name)
    mt = mat.vrm_addon_extension.mtoon1
    mode = spec["floats"].get("_Mode", 0)
    if mode == 1.0:
        mt.alpha_mode = "MASK"
        mt.alpha_cutoff = spec["floats"].get("_Cutoff", 0.5)
    elif mode == 3.0:
        mt.alpha_mode = "BLEND"
    mt.double_sided = spec["floats"].get("_Cull", 2.0) == 0.0
    has_emission = spec["floats"].get("_EnableEmission") and "_EmissionMap" in textures
    if not has_emission:
        mt.emissive_factor = (0.0, 0.0, 0.0)
    toon = mt.extensions.vrmc_materials_mtoon
    if base_image is not None:
        # the shade is the lit color darkened by shade_color_factor, not a flat color
        toon.shade_multiply_texture.index.source = base_image
    toon.shade_color_factor = (0.72, 0.68, 0.78)
    toon.shading_toony_factor = 0.85
    toon.parametric_rim_color_factor = (0.25, 0.9, 1.0)
    toon.parametric_rim_fresnel_power_factor = 4.0
    toon.parametric_rim_lift_factor = 0.05


def downscale_images(max_size):
    """VRChat textures are often 4K or 8K; a web renderer drawing 66 views of the model needs less."""
    for img in bpy.data.images:
        w, h = img.size
        if max(w, h) > max_size:
            f = max_size / max(w, h)
            img.scale(max(1, int(w * f)), max(1, int(h * f)))
            log("scaled", img.name, (w, h), "->", tuple(img.size))


def setup_materials(cfg):
    with open(cfg["materialsJson"], encoding="utf-8") as f:
        unity = json.load(f)
    for fbx_name, unity_name in cfg["materialMap"].items():
        mat = bpy.data.materials.get(fbx_name)
        if mat is None or unity_name not in unity:
            log("material not found", fbx_name, unity_name)
            continue
        setup_material(mat, unity[unity_name], cfg["textureDir"])
        log("material", fbx_name, "<-", unity_name, "alpha", mat.vrm_addon_extension.mtoon1.alpha_mode)


def unity_to_vrm_bone(human_name):
    """
    "LeftUpperArm" -> "left_upper_arm", "Left Thumb Proximal" -> "left_thumb_metacarpal". Unity counts the thumb from
    the proximal, VRM from the metacarpal.
    """
    words = re.findall(r"[A-Z][a-z]*", human_name.replace(" ", ""))
    name = "_".join(w.lower() for w in words)
    if "_thumb_" in name:
        name = name.replace("proximal", "metacarpal").replace("intermediate", "proximal")
    return name


def read_unity_humanoid(meta_path):
    """The bone -> human bone pairs of the model importer's humanDescription, the mapping the avatar used in Unity."""
    with open(meta_path, encoding="utf-8") as f:
        text = f.read()
    text = text[text.index("humanDescription:"):]
    return re.findall(r"- boneName: (.+)\n\s+humanName: (.+)\n", text)


def setup_humanoid(arm, cfg):
    hb = arm.data.vrm_addon_extension.vrm1.humanoid.human_bones
    if cfg.get("unityMeta"):
        for bone, human in read_unity_humanoid(cfg["unityMeta"]):
            prop = getattr(hb, unity_to_vrm_bone(human), None)
            if prop is None:
                log("no VRM bone for", human)
            elif bone.strip() in arm.data.bones:
                prop.node.bone_name = bone.strip()
    else:
        bpy.ops.vrm.assign_vrm1_humanoid_human_bones_automatically(armature_object_name=arm.name)
    assigned, missing = [], []
    for name in ("hips", "spine", "chest", "neck", "head", "left_upper_arm", "left_lower_arm", "left_hand",
                 "right_upper_arm", "right_lower_arm", "right_hand", "left_upper_leg", "left_lower_leg", "left_foot",
                 "right_upper_leg", "right_lower_leg", "right_foot", "left_eye", "right_eye"):
        bone = getattr(hb, name).node.bone_name
        (assigned if bone else missing).append(name + ("=" + bone if bone else ""))
    log("humanoid", assigned)
    if missing:
        log("humanoid MISSING", missing)


def bind(expression, mesh_name, key_name, weight=1.0):
    obj = bpy.data.objects.get(mesh_name)
    if obj is None or obj.data.shape_keys is None or key_name not in obj.data.shape_keys.key_blocks:
        log("no shape key", mesh_name, key_name)
        return
    b = expression.morph_target_binds.add()
    b.node.mesh_object_name = mesh_name
    b.index = key_name
    b.weight = weight


def setup_expressions(arm, cfg):
    bpy.ops.vrm.assign_vrm1_expressions_from_vrchat(armature_object_name=arm.name)
    expressions = arm.data.vrm_addon_extension.vrm1.expressions
    preset = expressions.preset
    face = cfg["faceMesh"]
    for name, keys in cfg.get("presetExpressions", {}).items():
        expression = getattr(preset, name)
        if len(expression.morph_target_binds) == 0:
            for key in keys:
                bind(expression, face, key)
    for name, keys in cfg.get("customExpressions", {}).items():
        bpy.ops.vrm.add_vrm1_expressions_custom_expression(armature_object_name=arm.name, custom_expression_name=name)
        expression = next(e for e in expressions.custom if e.custom_name == name)
        for key in keys:
            bind(expression, face, key)
    filled = [n for n, e in expressions.all_name_to_expression_dict().items() if len(e.morph_target_binds)]
    log("expressions", filled)


def prune_shape_keys(arm, cfg):
    """
    Keeps only the shape keys some expression uses, plus cfg["keepShapeKeys"] ({mesh: [names]}). three.js runs every
    morph target of a mesh in the vertex shader, and the Looking Glass draws each mesh once per view (66 times), so a
    face with 230 keys of which 40 are used costs most of the frame.
    """
    used = {}
    for expression in arm.data.vrm_addon_extension.vrm1.expressions.all_name_to_expression_dict().values():
        for b in expression.morph_target_binds:
            used.setdefault(b.node.mesh_object_name, set()).add(b.index)
    for mesh_name, names in cfg.get("keepShapeKeys", {}).items():
        used.setdefault(mesh_name, set()).update(names)
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.data.shape_keys is None:
            continue
        keep = used.get(obj.name, set())
        keys = list(obj.data.shape_keys.key_blocks)
        before = len(keys) - 1
        for kb in keys[1:]:
            if kb.name not in keep:
                obj.shape_key_remove(kb)
        if obj.data.shape_keys is not None and len(obj.data.shape_keys.key_blocks) == 1:
            # only the basis left: no morph targets at all for this mesh
            obj.shape_key_remove(obj.data.shape_keys.key_blocks[0])
        after = len(obj.data.shape_keys.key_blocks) - 1 if obj.data.shape_keys else 0
        log("shape keys", obj.name, before, "->", after)


def setup_springs(arm):
    bpy.ops.vrm.assign_spring_bone1_automatically(armature_object_name=arm.name)
    springs = arm.data.vrm_addon_extension.spring_bone1.springs
    log("springs", len(springs), "joints", sum(len(s.joints) for s in springs))


def setup_meta(arm, cfg):
    meta = arm.data.vrm_addon_extension.vrm1.meta
    meta.vrm_name = cfg["name"]
    if len(meta.authors) == 0:
        meta.authors.add()
    meta.authors[0].value = cfg.get("author", "unknown")


def main():
    cfg = load_config()
    arm = import_fbx(cfg["fbx"])
    arm.data.vrm_addon_extension.spec_version = "1.0"
    remove_meshes(cfg.get("removeMeshes", []))
    clean_shape_keys(cfg)
    setup_materials(cfg)
    downscale_images(cfg.get("maxTextureSize", 2048))
    setup_humanoid(arm, cfg)
    setup_expressions(arm, cfg)
    if cfg.get("pruneShapeKeys", True):
        prune_shape_keys(arm, cfg)
    setup_springs(arm)
    setup_meta(arm, cfg)
    tris = sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in bpy.data.objects if o.type == "MESH")
    log("triangles", tris)
    bpy.ops.object.select_all(action="DESELECT")
    result = bpy.ops.export_scene.vrm(filepath=cfg["output"], armature_object_name=arm.name, ignore_warning=True)
    log("export", result, cfg["output"])


main()
