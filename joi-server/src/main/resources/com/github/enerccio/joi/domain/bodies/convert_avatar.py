"""
Converts the FBX of an avatar (a VRChat one above all) into a VRM 1.0 model, headless in Blender with the "VRM format"
add-on (extensions.blender.org, id vrm). Joi's server runs it (ModelConversionService):

    Blender -b --factory-startup --python convert_avatar.py -- <config.json>

The config (JSON) names the files and what to change; everything but the files has a default:

    fbx            the FBX to convert
    output         where the VRM goes
    report         where the report goes (JSON, written after each step, so a failed run still tells what it found)
    name, author   the VRM's meta
    unityMeta      the FBX's .meta from Unity: its humanoid mapping (else the add-on guesses the bones)
    materialsJson  the Unity materials: {key: {name, file, textures: {property: path}, colors, floats}}
    textureDir     what the texture paths are relative to
    materialMap    {FBX material: key of a Unity material} (else matched by name)
    maxTextureSize textures are scaled down to it (2048)
    removeMeshes   meshes to leave out (the user's choice: props, alternative outfits)
    faceMesh       the mesh with the face keys (else the one with the most viseme and blink keys)
    bakeShapeKeys  {mesh: [keys]} made part of the mesh
    dropShapeKeys  patterns of keys to delete
    dropShapeKeyParts  keys to delete: names or parts of names, any case
    presetExpressions, customExpressions  {expression: [keys of the face mesh]} (else the moods are found by name)
    pruneShapeKeys keep only the keys expressions use and the toggles (true)

What is left out is the user's choice (removeMeshes, dropShapeKeys, bakeShapeKeys); nothing is filtered on its own.

What it does not carry over: the Poiyomi look (only the color, normal and emission textures go into MToon), PhysBones,
VRCFury, contacts, particles and animator states. Spring bones are not made here: Joi writes them into the VRM
afterwards (SpringBones, guessed from the bones' names and changed by the user).
"""
import json
import re
import sys
import traceback

import addon_utils
import bpy

ADDON = "bl_ext.blender_org.vrm"

# shape keys that show or hide a part ("Hide Halo", "Side Bangs Off", "Nail Remove"): toggles the body can switch,
# kept by the pruning
TOGGLE_KEYS = re.compile(r"\b(hide|remove|off|toggle)\b", re.IGNORECASE)

# the moods of VRM, found by the names of the face keys when the config gives none
MOOD_KEYS = {
    "happy": [r"smile", r"happy", r"joy", r"\^\^"],
    "angry": [r"ang(ry|er)", r"mad\b", r"rage"],
    "sad": [r"\bsad", r"sorrow", r"cry"],
    "relaxed": [r"relax", r"calm", r"sleepy", r"content"],
    "surprised": [r"surpris", r"shock", r"wow"],
}

report = {"meshes": [], "materials": [], "expressions": [], "humanoidMissing": [], "warnings": [], "triangles": 0}


def log(*args):
    print("[convert]", *args, flush=True)


def warn(text):
    report["warnings"].append(text)
    log("WARNING", text)


def write_report(cfg):
    if cfg.get("report"):
        with open(cfg["report"], "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1)


def load_config():
    path = sys.argv[sys.argv.index("--") + 1]
    with open(path, encoding="utf-8") as f:
        return json.load(f)



def enable_vrm_addon():
    """Enables the VRM add-on, or fails saying why. addon_utils.enable does not raise when the add-on can't be loaded (it
    returns None and prints the error), and bpy.ops answers any name, so neither tells by itself: what the add-on registers
    on an armature does."""
    errors = []
    try:
        module = addon_utils.enable(ADDON, default_set=True, handle_error=errors.append)
    except Exception as e:
        module = None
        errors.append(e)
    if module is None or not hasattr(bpy.types.Armature, "vrm_addon_extension"):
        if errors:
            why = "%s: %s" % (type(errors[-1]).__name__, errors[-1])
        elif module is None:
            why = "it is not installed for this Blender and user folder"
        else:
            why = "it was loaded but did not register its properties"
        raise RuntimeError("the VRM add-on (VRM format, extensions.blender.org) could not be loaded in Blender %s "
                           "(user folder %s): %s" % (bpy.app.version_string, bpy.utils.resource_path("USER"), why))


def has_operator(group, name):
    """Whether the operator exists: bpy.ops.<group>.<name> is there for any name, its RNA type only for a real one."""
    try:
        getattr(getattr(bpy.ops, group), name).get_rna_type()
        return True
    except (KeyError, AttributeError):
        return False

def triangles(obj):
    return sum(len(p.vertices) - 2 for p in obj.data.polygons)


def import_fbx(path):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    enable_vrm_addon()
    if not has_operator("export_scene", "vrm"):
        raise RuntimeError("the VRM add-on in this Blender can't export VRM: update it")
    bpy.ops.import_scene.fbx(filepath=path)
    armatures = [o for o in bpy.data.objects if o.type == "ARMATURE"]
    if not armatures:
        raise RuntimeError("the FBX has no armature: it is not an avatar")
    # props and helpers can bring their own small armature: the avatar's is the one with the most bones
    armatures.sort(key=lambda a: len(a.data.bones), reverse=True)
    if len(armatures) > 1:
        warn("the FBX has %d armatures; the one with the most bones (%s) is the avatar" % (len(armatures), armatures[0].name))
    return armatures[0]


def describe_meshes(remove):
    for obj in bpy.data.objects:
        if obj.type != "MESH":
            continue
        keys = obj.data.shape_keys
        report["meshes"].append({
            "name": obj.name,
            "triangles": triangles(obj),
            "shapeKeys": len(keys.key_blocks) - 1 if keys else 0,
            "materials": [s.material.name for s in obj.material_slots if s.material],
            "shapeKeyNames": [kb.name for kb in keys.key_blocks[1:]] if keys else [],
            "removed": obj.name in remove,
        })


def remove_meshes(names):
    for obj in list(bpy.data.objects):
        if obj.type == "MESH" and obj.name in names:
            log("removed mesh", obj.name)
            bpy.data.objects.remove(obj, do_unlink=True)


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
    parts = [p.lower() for p in cfg.get("dropShapeKeyParts", []) if p]
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.data.shape_keys is None:
            continue
        for key_name in cfg.get("bakeShapeKeys", {}).get(obj.name, []):
            bake_shape_key(obj, key_name)
        removed = 0
        for kb in list(obj.data.shape_keys.key_blocks)[1:]:
            # the separators ("Eye -------") carry nothing
            if any(p in kb.name.lower() for p in parts) or any(p.search(kb.name) for p in drop) or re.fullmatch(r"[^A-Za-z0-9]*|.*[-—]{5,}.*", kb.name):
                obj.shape_key_remove(kb)
                removed += 1
        if removed:
            log("dropped", removed, "shape keys of", obj.name)


def setup_material(mat, spec, tex_dir):
    mat.use_nodes = True
    nodes, links = mat.node_tree.nodes, mat.node_tree.links
    bsdf = next((n for n in nodes if n.type == "BSDF_PRINCIPLED"), None)
    if bsdf is None:
        bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    floats = spec.get("floats", {})

    def image_node(rel, non_color=False):
        try:
            img = bpy.data.images.load(tex_dir + "/" + rel, check_existing=True)
        except Exception:
            warn("texture not readable: " + rel)
            return None
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
    mode = floats.get("_Mode", 0)
    if "_MainTex" in textures:
        tex = image_node(textures["_MainTex"])
        if tex is not None:
            base_image = tex.image
            links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
            if mode in (1.0, 3.0):
                links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    if "_BumpMap" in textures:
        tex = image_node(textures["_BumpMap"], non_color=True)
        if tex is not None:
            normal = nodes.new("ShaderNodeNormalMap")
            links.new(tex.outputs["Color"], normal.inputs["Color"])
            links.new(normal.outputs["Normal"], bsdf.inputs["Normal"])
    has_emission = False
    if floats.get("_EnableEmission") and "_EmissionMap" in textures:
        tex = image_node(textures["_EmissionMap"])
        if tex is not None:
            links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
            bsdf.inputs["Emission Strength"].default_value = 1.0
            has_emission = True

    bpy.ops.vrm.convert_material_to_mtoon1(material_name=mat.name)
    mt = mat.vrm_addon_extension.mtoon1
    if mode == 1.0:
        mt.alpha_mode = "MASK"
        mt.alpha_cutoff = floats.get("_Cutoff", 0.5)
    elif mode == 3.0:
        mt.alpha_mode = "BLEND"
    mt.double_sided = floats.get("_Cull", 2.0) == 0.0
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
    return base_image is not None


def downscale_images(max_size):
    """VRChat textures are often 4K or 8K; a web renderer drawing 66 views of the model needs less."""
    for img in bpy.data.images:
        w, h = img.size
        if max(w, h) > max_size:
            f = max_size / max(w, h)
            img.scale(max(1, int(w * f)), max(1, int(h * f)))
            log("scaled", img.name, (w, h), "->", tuple(img.size))


def normalized(name):
    """'M_Switchblades' -> 'switchblade': what a material's name has in common with its Unity material."""
    name = re.sub(r"^(m|mat|material)[_ ]", "", name.strip(), flags=re.IGNORECASE)
    name = re.sub(r"[^a-z0-9]", "", name.lower())
    return name[:-1] if len(name) > 3 and name.endswith("s") else name


def match_by_name(fbx_name, unity):
    """The Unity material for an FBX material without a mapping: the same name, else one name containing the other."""
    wanted = normalized(fbx_name)
    if not wanted:
        return None
    exact = [k for k, spec in unity.items() if normalized(spec.get("name", "")) == wanted]
    if exact:
        return exact[0]
    near = [k for k, spec in unity.items()
            if normalized(spec.get("name", "")) and (wanted in normalized(spec["name"]) or normalized(spec["name"]) in wanted)]
    near.sort(key=lambda k: abs(len(normalized(unity[k]["name"])) - len(wanted)))
    return near[0] if near else None


def setup_materials(cfg):
    unity = {}
    if cfg.get("materialsJson"):
        with open(cfg["materialsJson"], encoding="utf-8") as f:
            unity = json.load(f)
    mapping = cfg.get("materialMap", {})
    used = {s.material for o in bpy.data.objects if o.type == "MESH" for s in o.material_slots if s.material}
    for mat in sorted(used, key=lambda m: m.name):
        key = mapping.get(mat.name)
        how = "meta"
        if key not in unity:
            key = match_by_name(mat.name, unity)
            how = "name"
        if key is None:
            report["materials"].append({"name": mat.name, "unity": None, "textured": False})
            warn("no Unity material for " + mat.name + ": it keeps the FBX's own")
            try:
                bpy.ops.vrm.convert_material_to_mtoon1(material_name=mat.name)
            except Exception:
                pass
            continue
        textured = setup_material(mat, unity[key], cfg.get("textureDir", "."))
        report["materials"].append({"name": mat.name, "unity": unity[key].get("name"), "by": how, "textured": textured,
                                    "alpha": mat.vrm_addon_extension.mtoon1.alpha_mode})
        log("material", mat.name, "<-", unity[key].get("name"), "(" + how + ")")


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
    if "humanDescription:" not in text:
        return []
    text = text[text.index("humanDescription:"):]
    return re.findall(r"- boneName: (.+)\n\s+humanName: (.+)\n", text)


REQUIRED_BONES = ("hips", "spine", "chest", "neck", "head", "left_upper_arm", "left_lower_arm", "left_hand",
                  "right_upper_arm", "right_lower_arm", "right_hand", "left_upper_leg", "left_lower_leg", "left_foot",
                  "right_upper_leg", "right_lower_leg", "right_foot", "left_eye", "right_eye")


def setup_humanoid(arm, cfg):
    hb = arm.data.vrm_addon_extension.vrm1.humanoid.human_bones
    pairs = read_unity_humanoid(cfg["unityMeta"]) if cfg.get("unityMeta") else []
    if pairs:
        for bone, human in pairs:
            prop = getattr(hb, unity_to_vrm_bone(human), None)
            if prop is None:
                log("no VRM bone for", human)
            elif bone.strip() in arm.data.bones:
                prop.node.bone_name = bone.strip()
        log("humanoid from the Unity .meta")
    else:
        bpy.ops.vrm.assign_vrm1_humanoid_human_bones_automatically(armature_object_name=arm.name)
        warn("no humanoid mapping from Unity: the bones were guessed")
    missing = [name for name in REQUIRED_BONES if not getattr(hb, name).node.bone_name]
    report["humanoidMissing"] = missing
    if missing:
        warn("humanoid bones missing: " + ", ".join(missing))


def bind(expression, mesh_name, key_name, weight=1.0):
    obj = bpy.data.objects.get(mesh_name)
    if obj is None or obj.data.shape_keys is None or key_name not in obj.data.shape_keys.key_blocks:
        log("no shape key", mesh_name, key_name)
        return False
    b = expression.morph_target_binds.add()
    b.node.mesh_object_name = mesh_name
    b.index = key_name
    b.weight = weight
    return True


def find_face_mesh(cfg):
    if cfg.get("faceMesh") and bpy.data.objects.get(cfg["faceMesh"]) is not None:
        return cfg["faceMesh"]
    best, best_score = None, -1
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.data.shape_keys is None:
            continue
        names = [kb.name.lower() for kb in obj.data.shape_keys.key_blocks]
        score = 1000 * sum(1 for n in names if n.startswith("vrc.v_") or "blink" in n) + len(names)
        if score > best_score:
            best, best_score = obj.name, score
    return best


def region(name):
    """The part of the face a key moves, by its prefix ('Eye - Sad' -> 'eye'), else the whole face."""
    match = re.match(r"\s*([A-Za-z]+)\s*[-_:.]", name)
    return match.group(1).lower() if match else ""


def side_pair(name, names):
    """'Eye - Blink Happy L' -> both it and 'Eye - Blink Happy R' when that exists: a mood moves both eyes."""
    match = re.match(r"(.*?)([ _.])([LR]|Left|Right)$", name)
    if not match:
        return [name]
    other = {"L": "R", "R": "L", "Left": "Right", "Right": "Left"}[match.group(3)]
    sibling = match.group(1) + match.group(2) + other
    return [name, sibling] if sibling in names else [name]


def auto_blink_keys(face):
    """
    Blink by the names of the face keys: one key per eye ('Eye - Blink L', the shortest such), else VRChat's
    'vrc.blink' for both; the eyes alone too.
    """
    obj = bpy.data.objects.get(face)
    if obj is None or obj.data.shape_keys is None:
        return {}
    names = [kb.name for kb in obj.data.shape_keys.key_blocks[1:]]

    def is_blink(n):
        return re.search(r"blink", n, re.IGNORECASE) and not re.search(r"happy|sleepy|sad|angry|wink", n, re.IGNORECASE)

    # a left key and its right one of the same naming ('Eye - Blink L' / 'Eye - Blink R', 'eyeBlinkLeft' / 'eyeBlinkRight')
    pairs = []
    for name in names:
        match = re.match(r"(.*?)(Left|L)$", name)
        if match and is_blink(name):
            right = match.group(1) + ("Right" if match.group(2) == "Left" else "R")
            if right in names:
                pairs.append((name, right))
    # the names that say the part of the face ('Eye - ') first, then the plainest
    pairs.sort(key=lambda pair: (region(pair[0]) == "", len(pair[0])))
    left, right = pairs[0] if pairs else (None, None)
    keys = {}
    if left and right:
        keys["blink"] = [left, right]
        keys["blink_left"] = [left]
        keys["blink_right"] = [right]
    elif "vrc.blink" in names:
        keys["blink"] = ["vrc.blink"]
    return keys


def auto_mood_keys(face):
    """
    For each mood, the face keys whose names say it: in each part of the face (eyes, mouth, brows...) the shortest such
    name (the plain one, not 'smile 2 teeth'), with its other side.
    """
    obj = bpy.data.objects.get(face)
    if obj is None or obj.data.shape_keys is None:
        return {}
    names = [kb.name for kb in obj.data.shape_keys.key_blocks[1:] if not kb.name.lower().startswith("vrc.")]
    moods = {}
    for mood, patterns in MOOD_KEYS.items():
        found = [n for n in names if any(re.search(p, n, re.IGNORECASE) for p in patterns)]
        by_region = {}
        for name in found:
            by_region.setdefault(region(name), []).append(name)
        if len(by_region) > 1:
            # keys without a part of the face (ARKit's 'mouthSmileLeft') only when nothing better says the mood
            by_region.pop("", None)
        keys = []
        for candidates in by_region.values():
            keys.extend(side_pair(min(candidates, key=len), names))
        if keys:
            moods[mood] = keys
    return moods


def setup_expressions(arm, cfg):
    bpy.ops.vrm.assign_vrm1_expressions_from_vrchat(armature_object_name=arm.name)
    expressions = arm.data.vrm_addon_extension.vrm1.expressions
    preset = expressions.preset
    face = find_face_mesh(cfg)
    report["faceMesh"] = face
    if face is None:
        warn("no mesh with face keys: no expressions")
        return
    log("face mesh", face)
    presets = cfg.get("presetExpressions") or {**auto_blink_keys(face), **auto_mood_keys(face)}
    for name, keys in presets.items():
        expression = getattr(preset, name, None)
        if expression is not None and len(expression.morph_target_binds) == 0:
            for key in keys:
                bind(expression, face, key)
    for name, keys in cfg.get("customExpressions", {}).items():
        bpy.ops.vrm.add_vrm1_expressions_custom_expression(armature_object_name=arm.name, custom_expression_name=name)
        expression = next(e for e in expressions.custom if e.custom_name == name)
        for key in keys:
            bind(expression, face, key)
    filled = {n: [b.index for b in e.morph_target_binds]
              for n, e in expressions.all_name_to_expression_dict().items() if len(e.morph_target_binds)}
    report["expressions"] = [{"name": n, "keys": keys} for n, keys in filled.items()]
    log("expressions", list(filled))


def prune_shape_keys(arm, cfg):
    """
    Keeps only the shape keys some expression uses, the toggles (TOGGLE_KEYS), and cfg["keepShapeKeys"] ({mesh: [names]}).
    three.js runs every
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
            if kb.name not in keep and not TOGGLE_KEYS.search(kb.name):
                obj.shape_key_remove(kb)
        if obj.data.shape_keys is not None and len(obj.data.shape_keys.key_blocks) == 1:
            # only the basis left: no morph targets at all for this mesh
            obj.shape_key_remove(obj.data.shape_keys.key_blocks[0])
        after = len(obj.data.shape_keys.key_blocks) - 1 if obj.data.shape_keys else 0
        log("shape keys", obj.name, before, "->", after)


def setup_meta(arm, cfg):
    meta = arm.data.vrm_addon_extension.vrm1.meta
    meta.vrm_name = cfg.get("name") or "avatar"
    if len(meta.authors) == 0:
        meta.authors.add()
    meta.authors[0].value = cfg.get("author") or "unknown"


def main():
    cfg = load_config()
    try:
        log("step import")
        arm = import_fbx(cfg["fbx"])
        arm.data.vrm_addon_extension.spec_version = "1.0"
        remove = set(cfg.get("removeMeshes", []))
        describe_meshes(remove)
        write_report(cfg)
        remove_meshes(remove)
        log("step shape keys")
        clean_shape_keys(cfg)
        log("step materials")
        setup_materials(cfg)
        downscale_images(cfg.get("maxTextureSize", 2048))
        write_report(cfg)
        log("step humanoid")
        setup_humanoid(arm, cfg)
        log("step expressions")
        setup_expressions(arm, cfg)
        if cfg.get("pruneShapeKeys", True):
            prune_shape_keys(arm, cfg)
        setup_meta(arm, cfg)
        report["triangles"] = sum(triangles(o) for o in bpy.data.objects if o.type == "MESH")
        log("triangles", report["triangles"])
        write_report(cfg)
        log("step export")
        bpy.ops.object.select_all(action="DESELECT")
        result = bpy.ops.export_scene.vrm(filepath=cfg["output"], armature_object_name=arm.name, ignore_warning=True)
        if "FINISHED" not in result:
            raise RuntimeError("the VRM export failed: " + str(result))
        log("done", cfg["output"])
    except Exception as e:
        report["error"] = str(e)
        write_report(cfg)
        traceback.print_exc()
        log("FAILED", e)
        sys.exit(1)


main()
