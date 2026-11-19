package com.github.enerccio.joi.loc;

import com.github.enerccio.marginalia.loc.Language;
import com.github.enerccio.marginalia.loc.TextBundleBase;

/**
 * Joi's texts in English, the default language: every key of {@link J_L} has one here.
 */
public class JoiLocalizationBundleEN extends TextBundleBase<J_L> {

    public JoiLocalizationBundleEN() {
        super(J_L.class);
    }

    @Override
    public Language getLanguage() {
        return Language.EN;
    }

    @Override
    protected void loadMessages() {
        // --- LABEL ---
        setValue(J_L.LABEL_APP_DESC, "Your personal assistant");
        setValue(J_L.LABEL_LOGOUT, "Log out");
        setValue(J_L.LABEL_MODELS, "Models");
        setValue(J_L.LABEL_ADD_MODEL, "Add model");
        setValue(J_L.LABEL_SHARED, "Shared");
        setValue(J_L.LABEL_CLONE_MODEL, "Clone model");
        setValue(J_L.LABEL_TEXT_MODELS, "Text Models");
        setValue(J_L.LABEL_USED_FOR, "Used for");
        setValue(J_L.LABEL_ADMIN, "Administration");
        setValue(J_L.LABEL_USERS, "Users");
        setValue(J_L.LABEL_ADD_USER, "Add user");
        setValue(J_L.LABEL_TTS_MODELS, "TTS Models");
        setValue(J_L.LABEL_ADD_TTS_MODEL, "Add TTS model");
        setValue(J_L.LABEL_TTS_MODEL, "TTS model");
        setValue(J_L.LABEL_VOICE_ACTORS, "Voice Actors");
        setValue(J_L.LABEL_ADD_ACTOR, "Add voice actor");
        setValue(J_L.LABEL_ACTOR, "Voice actor");
        setValue(J_L.LABEL_NEW_ACTOR, "New actor");
        setValue(J_L.LABEL_TAGLINE, "Tagline");
        setValue(J_L.LABEL_VOICE, "Voice");
        setValue(J_L.LABEL_VOICE_FOR_MOOD_OR_LANGUAGE, "A voice for a mood or a language");
        setValue(J_L.LABEL_WHEN, "When");
        setValue(J_L.LABEL_MOOD_NAME, "Mood: %s");
        setValue(J_L.LABEL_LANGUAGE_NAME, "Language: %s");
        setValue(J_L.LABEL_ADD_CLIP, "Add a clip");
        setValue(J_L.LABEL_CLIP_TEXT, "Words spoken in the clip");
        setValue(J_L.LABEL_SPEECH_MODEL, "Speech model");
        setValue(J_L.LABEL_ADDRESS, "Address");
        setValue(J_L.LABEL_SERVER_IS_LOCAL, "The server is on this machine or on my network");
        setValue(J_L.LABEL_TIMEOUT_SECONDS, "Timeout of a request (s)");
        setValue(J_L.LABEL_AUDIO_FORMAT, "Audio format");
        setValue(J_L.LABEL_SPEED, "Speed");
        setValue(J_L.LABEL_CFG_WEIGHT, "CFG weight");
        setValue(J_L.LABEL_CHUNK_SIZE, "Chunk size (characters)");
        setValue(J_L.LABEL_PRECISION, "Precision");
        setValue(J_L.LABEL_SEND_CLIP_TEXT, "Send the words of the clip to Higgs");
        setValue(J_L.LABEL_TRY_VOICE, "Try the voice");
        setValue(J_L.LABEL_TRY_WITH, "Try with");
        setValue(J_L.LABEL_SAMPLE_TEXT, "Text");
        setValue(J_L.LABEL_SPEAK, "Speak");
        setValue(J_L.LABEL_USER_SETTINGS, "User Settings");
        setValue(J_L.LABEL_GENERAL, "General");
        setValue(J_L.LABEL_PROMPTS, "Prompts");
        setValue(J_L.LABEL_PERSONALITIES, "Personalities");
        setValue(J_L.LABEL_MOODS, "Moods");
        setValue(J_L.LABEL_ASSISTANT_NAME, "Assistant name");
        setValue(J_L.LABEL_PERSONALITY, "Personality");
        setValue(J_L.LABEL_MOOD, "Mood");
        setValue(J_L.LABEL_CHAT_MODEL, "Chat model");
        setValue(J_L.LABEL_REASONING_MODEL, "Deep reasoning model");
        setValue(J_L.LABEL_AUXILIARY_MODEL, "Auxiliary model");
        setValue(J_L.LABEL_VOICE_ACTOR, "Voice actor");
        setValue(J_L.LABEL_DAY_BOUNDARY, "New day starts at");
        setValue(J_L.LABEL_PAUSE_MINUTES, "Conversation pause (minutes)");
        setValue(J_L.LABEL_MASTER_TEMPLATE, "Master system template");
        setValue(J_L.LABEL_SUMMARY_PROMPT, "Conversation summary prompt");
        setValue(J_L.LABEL_DAY_SUMMARY_PROMPT, "Day summary prompt");
        setValue(J_L.LABEL_FACTS_PROMPT, "Memories prompt (facts to remember)");
        setValue(J_L.LABEL_SELF_IMAGE_PROMPT, "Appearance prompt (a model described from its snapshots)");
        setValue(J_L.LABEL_PART_IMAGE_PROMPT, "Part prompt (a toggle described from close-ups, shown and hidden)");
        setValue(J_L.LABEL_BODY_PROMPT, "Body section (the body she is in, put into {{#body}})");
        setValue(J_L.LABEL_PREVIEW_SYSTEM_PROMPT, "Preview the system prompt");
        setValue(J_L.LABEL_KEY, "Key");
        setValue(J_L.LABEL_STATE, "State");
        setValue(J_L.LABEL_BUILT_IN, "Built-in");
        setValue(J_L.LABEL_CHANGED, "Changed");
        setValue(J_L.LABEL_OWN, "Your own");
        setValue(J_L.LABEL_ADD_PERSONALITY, "Add personality");
        setValue(J_L.LABEL_ADD_MOOD, "Add mood");
        setValue(J_L.LABEL_RESET_TO_DEFAULT, "Reset to the default");
        setValue(J_L.LABEL_MOOD_EXPRESSIONS, "How the moods sound in this personality");
        setValue(J_L.LABEL_WEIGHT, "Roll weight");
        setValue(J_L.LABEL_DELIVERY, "Voice delivery");
        setValue(J_L.LABEL_PROMPT, "Prompt");
        setValue(J_L.LABEL_USER_NAME, "Your name");
        setValue(J_L.LABEL_DISCARD_CHANGES, "Discard changes");
        setValue(J_L.LABEL_JOIS, "Jois");
        setValue(J_L.LABEL_CREATE_JOI, "Create Joi");
        setValue(J_L.LABEL_LAST_CHATTED, "Last chatted");
        setValue(J_L.LABEL_ABOUT, "About");
        setValue(J_L.LABEL_TEMPLATES, "Templates");
        setValue(J_L.LABEL_MEMORIES, "Memories");
        setValue(J_L.LABEL_SUMMARIES, "Summaries");
        setValue(J_L.LABEL_CHATS, "Chats");
        setValue(J_L.LABEL_CREATE_TOKEN, "Create token");
        setValue(J_L.LABEL_CONNECT_DEVICE, "Connect a device");
        setValue(J_L.LABEL_TOKEN, "Token");
        setValue(J_L.LABEL_API_ACCESS, "API access");
        setValue(J_L.LABEL_ALLOW_TOKENS, "Tokens");
        setValue(J_L.LABEL_ALLOW_OAUTH, "OAuth (devices)");
        setValue(J_L.LABEL_ACCESS_TOKEN_MINUTES, "OAuth access token lifetime (minutes)");
        setValue(J_L.LABEL_REFRESH_TOKEN_DAYS, "OAuth refresh token lifetime (days)");
        setValue(J_L.LABEL_DEVICE_CODE, "Code");
        setValue(J_L.LABEL_APPROVE, "Approve");
        setValue(J_L.LABEL_DENY, "Deny");
        setValue(J_L.LABEL_FIRST_ADMINISTRATOR, "the first administrator");
        setValue(J_L.LABEL_REASONING, "Reasoning");
        setValue(J_L.LABEL_EVENT, "Event");
        setValue(J_L.LABEL_BODIES, "Bodies");
        setValue(J_L.LABEL_ADD_3D_MODEL, "Add model");
        setValue(J_L.LABEL_NEW_3D_MODEL, "New model");
        setValue(J_L.LABEL_3D_MODELS, "Models");
        setValue(J_L.LABEL_SELF_IMAGE, "How she looks in it");
        setValue(J_L.LABEL_APPEARANCE_CORE, "How she looks (what never changes)");
        setValue(J_L.LABEL_DESCRIBED_PARTS, "Parts described apart");
        setValue(J_L.LABEL_PART_SHOWN, "shown");
        setValue(J_L.LABEL_PART_HIDDEN, "hidden");
        setValue(J_L.LABEL_DESCRIBE_TOGGLE, "Describe");
        setValue(J_L.MSG_DESCRIBED_WITH_GROUP, "Described with the group %s.");
        setValue(J_L.MSG_SHOWN_BY_DEFAULT_HINT, "Shown by default: the preview and the snapshots show the model so, and a new body comes so.");
        setValue(J_L.LABEL_TOGGLE_GROUPS, "Groups");
        setValue(J_L.MSG_TOGGLE_GROUPS_HINT, "Parts that only make sense together, described as a whole: name the states of the group (eyes: blindfold, bare) and set its toggles in each. Each state gets a close-up and a sentence.");
        setValue(J_L.LABEL_ADD_GROUP, "Add a group");
        setValue(J_L.LABEL_GROUP_NAME, "Group name");
        setValue(J_L.LABEL_GROUP_TOGGLES, "Toggles");
        setValue(J_L.LABEL_ADD_STATE, "Add a state");
        setValue(J_L.LABEL_STATE_NAME, "State");
        setValue(J_L.LABEL_TRY_STATE, "Show it on the preview");
        setValue(J_L.LABEL_DEFAULT_STATE, "default");
        setValue(J_L.MSG_NO_DEFAULT_STATE, "Mark the state she is in by default (the least covered reads best: blindfold off, so the blindfold is what changes). Until then the shots take the toggles' own defaults and every state's sentence is added to her look.");
        setValue(J_L.MSG_DEFAULT_STATE_HINT, "She is in this state by default: the shots show it and her description has it; the other states are described as what changes.");
        setValue(J_L.MSG_DESCRIBE_TOGGLE_HINT, "Described apart, shown and hidden, from close-ups: her description follows the part when it is switched.");
        setValue(J_L.MSG_BODY_APPEARANCE_HINT, "The model's description, for the state of the body. Described and edited in the model's dialog (Bodies tab of the workspace).");
        setValue(J_L.LABEL_TAKE_SNAPSHOTS, "Take snapshots");
        setValue(J_L.LABEL_REFERENCE_IMAGES, "Your images");
        setValue(J_L.LABEL_UPLOAD_IMAGES, "Upload images");
        setValue(J_L.LABEL_BODY_NOTES, "What you know about it");
        setValue(J_L.LABEL_PROPOSE_DESCRIPTION, "Propose a description");
        setValue(J_L.LABEL_PROPOSED_DESCRIPTION, "Proposed description");
        setValue(J_L.LABEL_PROPOSED_TOGGLE_NAMES, "Proposed names of the toggles");
        setValue(J_L.LABEL_USE_PROPOSAL, "Use it");
        setValue(J_L.LABEL_DISCARD_PROPOSAL, "Discard");
        setValue(J_L.MSG_SELF_IMAGE_HINT, "The snapshots, your images and notes go to your deep reasoning model (else your chat model) when it takes images. It describes what never changes, and each toggle marked Describe from its close-ups, shown and hidden. You review it before it is used.");
        setValue(J_L.MSG_NO_SNAPSHOTS, "No snapshots yet: they are taken of the model's VRM, with a close-up of each part described apart.");
        setValue(J_L.MSG_SNAPSHOTS_TAKEN, "Taken %s, with %d close-ups.");
        setValue(J_L.MSG_SNAPSHOTS_OUTDATED, "The model changed since: take them again.");
        setValue(J_L.MSG_SNAPSHOTS_FAILED, "The snapshots failed: %s");
        setValue(J_L.MSG_DESCRIBED_CURRENT, "The description was proposed from this model.");
        setValue(J_L.MSG_DESCRIBED_OUTDATED, "The model changed since the description was proposed: take new snapshots and ask again.");
        setValue(J_L.MSG_BODY_NOTES_HINT, "What a toggle is, the outfit's name, what the images can't show.");
        setValue(J_L.MSG_PROPOSAL_RUNNING, "%s is looking at the images… %s");
        setValue(J_L.MSG_PROPOSAL_FAILED, "The proposal failed: %s");
        setValue(J_L.MSG_PROPOSAL_FROM, "Proposed by %s, %s. Edit it and keep the names you want, then use it.");
        setValue(J_L.MSG_NO_VISION_MODEL, "Neither your deep reasoning model nor your chat model takes images: mark one as multimodal in Models.");
        setValue(J_L.LABEL_ADD_BODY, "Add body");
        setValue(J_L.LABEL_NEW_BODY, "New body");
        setValue(J_L.LABEL_BODY_MODEL, "Model");
        setValue(J_L.LABEL_LOOKING_GLASS_MODEL, "Model for the Looking Glass");
        setValue(J_L.LABEL_SAME_AS_FULL_MODEL, "The same model");
        setValue(J_L.LABEL_APPEARANCE, "How she looks");
        setValue(J_L.LABEL_ACTIVE_BODY, "Her body now");
        setValue(J_L.LABEL_SWITCH_TO_BODY, "Put her into this body");
        setValue(J_L.LABEL_PREVIEW_FULL, "Full model");
        setValue(J_L.LABEL_PREVIEW_LOOKING_GLASS, "Looking Glass");
        setValue(J_L.LABEL_USED_BY_BODIES, "In bodies");
        setValue(J_L.MSG_NO_BODIES, "She has no body yet. Add one: it is made of your models (the Bodies tab of the workspace).");
        setValue(J_L.MSG_BODY_MODEL_HINT, "A model with a VRM: converted, or uploaded as a .vrm or .glb. The Looking Glass can show a lighter one.");
        setValue(J_L.MSG_APPEARANCE_HINT, "Written into her prompt, in the second person; the parts described apart are added for the state they are in.");
        setValue(J_L.MSG_BODY_CAPABILITIES, "%d expressions · %d parts she can switch (the ones described apart). Name and describe them in the model's dialog.");
        setValue(J_L.MSG_BODY_NO_MODEL, "Choose a model to see the body.");
        setValue(J_L.MSG_MODEL_IN_USE, "This model is a part of these bodies: %s. Choose another model for them first.");
        setValue(J_L.HELP_BODY_EVENTS, "Once you have talked, she is told when she gets a body, is put into another one or loses hers.");
        setValue(J_L.LABEL_FORMAT, "Format");
        setValue(J_L.LABEL_MAIN_FILE, "Main file");
        setValue(J_L.LABEL_FILES, "Files");
        setValue(J_L.LABEL_UPLOAD_3D_MODEL, "Upload a model");
        setValue(J_L.LABEL_REPLACE_3D_MODEL, "Replace the model");
        setValue(J_L.LABEL_CONVERSION, "Conversion to VRM");
        setValue(J_L.LABEL_AVATAR_FBX, "Avatar FBX");
        setValue(J_L.LABEL_MAX_TEXTURE_SIZE, "Largest texture");
        setValue(J_L.LABEL_MESHES, "Meshes");
        setValue(J_L.LABEL_SHAPE_KEYS, "Shape keys");
        setValue(J_L.LABEL_DROP_SHAPE_KEYS, "Shape keys to delete: names or parts of names, one per line");
        setValue(J_L.LABEL_BAKE_SHAPE_KEYS, "Shape keys always on (mesh / key, one per line)");
        setValue(J_L.LABEL_CONVERT, "Convert to VRM");
        setValue(J_L.LABEL_CONVERT_AGAIN, "Convert again");
        setValue(J_L.LABEL_LOG, "Log");
        setValue(J_L.LABEL_PREVIEW_UPLOADED, "Uploaded");
        setValue(J_L.LABEL_PREVIEW_CONVERTED, "Converted VRM");
        setValue(J_L.LABEL_CAPABILITIES, "What it can do");
        setValue(J_L.LABEL_EXPRESSIONS, "Expressions");
        setValue(J_L.LABEL_TOGGLES, "Toggles");

        // --- ENUM ---
        setValue(J_L.ENUM_TEXT_LLM_TYPE_AUXILIARY, "Auxiliary");
        setValue(J_L.ENUM_TEXT_LLM_TYPE_CHAT, "Chat");
        setValue(J_L.ENUM_TEXT_LLM_TYPE_DEEP_REASONING, "Deep reasoning");
        setValue(J_L.ENUM_TTS_MODEL_TURBO, "Chatterbox Turbo (English, sound tags)");
        setValue(J_L.ENUM_TTS_MODEL_ORIGINAL, "Chatterbox (English, exaggeration)");
        setValue(J_L.ENUM_TTS_MODEL_MULTILINGUAL, "Chatterbox Multilingual (23 languages)");
        setValue(J_L.ENUM_TTS_MODEL_HIGGS, "Higgs Audio v3 in ComfyUI (emotions, sounds; non-commercial license)");
        setValue(J_L.ENUM_ACCESS_TOKEN_TOKEN, "Token");
        setValue(J_L.ENUM_ACCESS_TOKEN_OAUTH, "Device");

        // --- HELP ---
        setValue(J_L.HELP_TEXT_LLM_TYPES, "What Joi may use this model for: Chat (the conversation, fast replies), Deep "
                + "reasoning (long tasks and tool loops) and Auxiliary (small background jobs such as classifications and "
                + "summaries). A model can do several.");
        setValue(J_L.HELP_TTS_ADDRESS, "The base address of the speech server: Chatterbox-TTS-Server (for example "
                + "http://localhost:8004) or, for Higgs Audio v3, ComfyUI (for example http://localhost:8188).");
        setValue(J_L.HELP_SERVER_IS_LOCAL, "Allows the addresses of this machine and the local network; leave it off for a server in the "
                + "cloud.");
        setValue(J_L.HELP_TTS_GPU_LOCK, "The GPU lock of the server's GPU: every request waits for its turn on the GPU, and the lock's "
                + "start-up steps load the server first. A lock that also loads the conversation model keeps both "
                + "loaded. Empty: no lock.");
        setValue(J_L.HELP_SPEECH_MODEL, "The model the server must have loaded. Another model on the same server is another TTS model.");
        setValue(J_L.HELP_PRECISION, "The precision Higgs computes in. fp32 is the safe choice: bf16 gave gibberish and wrong voices "
                + "on an AMD card. bf16 needs about half the memory.");
        setValue(J_L.HELP_SEND_CLIP_TEXT, "Experimental, keep it off: Higgs gets the words spoken in the voice clip with it. In tests it "
                + "changed the voice.");
        setValue(J_L.HELP_TTS_TEST, "Takes the GPU lock (its start-up steps load the server when the GPU holds something else) and "
                + "asks the server whether the model is loaded. Nothing is spoken.");
        setValue(J_L.HELP_ACTOR, "The voice is a reference clip (one of your audio resources, or a new one) or a voice file of "
                + "the speech server. A mood or a language can have a voice of its own: a whisper, a shout, a clip "
                + "that speaks Japanese. The language wins over the mood; without a voice for them the main voice "
                + "speaks.");
        setValue(J_L.HELP_ASSISTANT_NAME, "What the assistant is called; empty for Joi. Her prompt uses it as {{char}}.");
        setValue(J_L.HELP_INHERITED_DEFAULT, "Empty uses the built-in default, shown greyed out. The (i) icon can fill it in for editing.");
        setValue(J_L.HELP_MASTER_TEMPLATE, "The system prompt of a chat. Sections such as {{#memories}}...{{/memories}} are left out when "
                + "there is nothing to put in them. Tools, approvals and the privacy mode are added around it by "
                + "the server.");
        setValue(J_L.HELP_PERSONALITY, "Who the assistant is. It changes her tone and banter, never what she does.");
        setValue(J_L.HELP_MOOD_EXPRESSION, "Empty uses the mood's own text.");
        setValue(J_L.HELP_PAUSE_MINUTES, "Minutes without talking after which the live conversation steps back to the open ones.");
        setValue(J_L.HELP_KEY, "A short id of letters, digits and dashes; it can't be changed later.");
        setValue(J_L.HELP_WEIGHT, "How likely the daily roll picks it; 0 never rolls it (it can still be set by hand).");
        setValue(J_L.HELP_CHAT_MODEL, "Only the text models marked for this use are listed.");
        setValue(J_L.HELP_USER_NAME, "What the assistant calls you; empty for the name of your account. Her prompt uses it as {{user}}.");
        setValue(J_L.HELP_JOI_INHERITED, "Empty fields use your User Settings, shown greyed out.");
        setValue(J_L.HELP_JOI_PERSONA_EVENTS, "Once you have talked, she is told when her name, your name or her personality changes.");
        setValue(J_L.HELP_ACTOR_DESCRIPTION, "How the voice sounds, in words (\"low and warm, a little husky\"). The assistant reads it: she is told when it becomes her voice, and it is in her system prompt.");
        setValue(J_L.HELP_API_ACCESS, "How nodes and other clients may sign in to the API (joi-protocol). Turning a way off stops its tokens and devices at once; turning it on again brings them back.");
        setValue(J_L.HELP_NO_AUTH, "No authentication is on in the configuration (joi.api.no-auth): a call without credentials acts as %s. Only for development and localhost setups.");
        setValue(J_L.HELP_USER_ACCESS, "The tokens and devices that may use the API as you. The API is at %s.");
        setValue(J_L.HELP_DEVICE_CODE, "The code the device shows, like WDJB-MJHT.");

        // --- PLACEHOLDER ---
        setValue(J_L.PLACEHOLDER_TAGLINE, "What the voice is for, or any tag");
        setValue(J_L.PLACEHOLDER_VOICE, "A clip, or a voice file of the server (Henry.wav)");
        setValue(J_L.PLACEHOLDER_STORED_KEY, "Stored. Type to replace it.");
        setValue(J_L.PLACEHOLDER_SAMPLE_TEXT, "Hello, this is how I sound. Shall we begin?");

        // --- MSG ---
        setValue(J_L.MSG_TTS_MODEL_LOADED, "The server answers, the model is loaded (%s)");
        setValue(J_L.MSG_TTS_NO_MODEL, "The server answers, but no model is loaded: let the start-up steps of the GPU lock load it");
        setValue(J_L.MSG_CONNECTION_FAILED, "Connection failed: %s");
        setValue(J_L.MSG_CLIP_NOT_ADDED, "The clip could not be added: %s");
        setValue(J_L.MSG_NO_TTS_MODEL, "Add a TTS model first");
        setValue(J_L.MSG_SPEECH_FAILED, "The speech could not be made: %s");
        setValue(J_L.MSG_CANNOT_DELETE_SELF, "You can't delete yourself");
        setValue(J_L.MSG_SETTINGS_SAVED, "Settings saved");
        setValue(J_L.MSG_INVALID_KEY, "Use letters, digits and dashes only");
        setValue(J_L.MSG_KEY_TAKEN, "This key is already used");
        setValue(J_L.MSG_TEMPLATE_INVALID, "The template is not valid: %s");
        setValue(J_L.MSG_UNSAVED_SETTINGS, "Unsaved changes");
        setValue(J_L.MSG_NOTHING_YET, "Nothing here yet.");
        setValue(J_L.MSG_TOKEN_CREATED, "Copy the token now: it is not shown again.");
        setValue(J_L.MSG_DEVICE_WANTS, "%s wants to use Joi as you.");
        setValue(J_L.MSG_DEVICE_APPROVED, "The device is connected.");
        setValue(J_L.MSG_DEVICE_UNKNOWN, "No device waits for this code: it may have expired.");
        setValue(J_L.MSG_API_OFF, "The administrator has turned off tokens and devices.");
        setValue(J_L.MSG_LOADING_EARLIER_MESSAGES, "Loading earlier messages…");
        setValue(J_L.MSG_NO_CHATS, "No chats yet.");
        setValue(J_L.MSG_NO_3D_MODEL_FILE, "No file yet: upload the model.");
        setValue(J_L.MSG_3D_MODEL_UPLOAD_HINT, "A .vrm or .glb is one file with everything inside. A model of several files (an .fbx or a .gltf with its textures) goes in one .zip. A VRChat avatar can be uploaded as its .unitypackage.");
        setValue(J_L.MSG_NOT_A_3D_MODEL, "Not a model: upload a .vrm, .glb, .gltf, .fbx, or a .zip with one of them.");
        setValue(J_L.MSG_3D_MODEL_UPLOADED, "The model is uploaded.");
        setValue(J_L.MSG_CONVERTING, "Converting… this takes a few minutes; the dialog can be closed.");
        setValue(J_L.MSG_CONVERTED, "Converted %s · %s");
        setValue(J_L.MSG_CONVERSION_FAILED, "The conversion failed: %s");
        setValue(J_L.MSG_MESHES_HINT, "Uncheck the meshes to leave out. They are known after the first conversion.");
        setValue(J_L.MSG_PACKAGE_PREVIEW, "A Unity package is shown once it is converted.");
        setValue(J_L.MSG_CONVERSION_SUMMARY, "%,d triangles · %d of %d materials matched (%d textured) · %d expressions · %d spring chains");
        setValue(J_L.MSG_MISSING_BONES, "Bones not mapped: %s");
        setValue(J_L.MSG_ALREADY_VRM, "Already a VRM: nothing to convert.");
        setValue(J_L.MSG_NO_FBX, "Nothing to convert: the upload has no FBX.");
        setValue(J_L.MSG_CAPABILITIES_HINT, "Click an expression to try it on the preview; uncheck a toggle to hide that part. The names are what she and you will call them.");
        setValue(J_L.MSG_NO_CAPABILITIES, "Known once the model is a VRM: convert it, or upload a .vrm.");
        setValue(J_L.MSG_HUMANOID_BONES, "%d humanoid bones");

        // --- DESC ---
        setValue(J_L.DESC_TEMPLATE_PERSONALITY, "The personality, rendered ({{user}} and {{char}} work in it).");
        setValue(J_L.DESC_TEMPLATE_MOOD, "Today's mood as it sounds in the personality.");
        setValue(J_L.DESC_TEMPLATE_PROFILE, "The short block about the user that is always in the prompt.");
        setValue(J_L.DESC_TEMPLATE_MEMORIES, "What the assistant remembers that matters now: facts and past episodes.");
        setValue(J_L.DESC_TEMPLATE_CONVERSATIONS, "Short summaries of the other open conversations.");
        setValue(J_L.DESC_TEMPLATE_BODY, "The current body (avatar): what it can do and its state.");
        setValue(J_L.DESC_TEMPLATE_NODE, "The device the user talks through and what it offers.");
        setValue(J_L.DESC_TEMPLATE_CONVERSATION, "The transcript of the conversation.");
        setValue(J_L.DESC_TEMPLATE_DAY, "The day, as a date.");
        setValue(J_L.DESC_TEMPLATE_SUMMARIES, "The summaries of the day's conversations.");
        setValue(J_L.DESC_TEMPLATE_KNOWN_MEMORIES, "The facts already remembered.");
        setValue(J_L.DESC_TEMPLATE_BODY_NAME, "The body's name.");
        setValue(J_L.DESC_TEMPLATE_MODEL_NAME, "The model's name.");
        setValue(J_L.DESC_TEMPLATE_PART_NAME, "What the part is called: the toggle's name, or the group's.");
        setValue(J_L.DESC_TEMPLATE_PART_SOURCE, "The mesh, or the mesh / the shape key, that shows or hides the part; the names of a group's toggles.");
        setValue(J_L.DESC_TEMPLATE_PART_STATES, "The states of the part, one per line and numbered in the order of the images: \"1 | the part shown (default)\", or a group's \"1 | bare: blindfold hidden, iris shown\"; the default state is marked.");
        setValue(J_L.DESC_TEMPLATE_DESCRIBED_PARTS, "The names of the parts described apart (described as the renders show them; their other states are described apart); empty when none.");
        setValue(J_L.DESC_TEMPLATE_APPEARANCE, "How she looks; empty when nobody has described it.");
        setValue(J_L.DESC_TEMPLATE_EXPRESSIONS, "The expressions her face can show (moods and custom ones); empty when none.");
        setValue(J_L.DESC_TEMPLATE_PARTS, "The parts she can switch (only the ones described apart): a toggle with (shown) or (hidden), a group with its state and the others (\"eyes: bare (or blindfold)\"); empty when none.");
        setValue(J_L.DESC_TEMPLATE_OTHER_BODIES, "The names of her other bodies; empty when none.");
        setValue(J_L.DESC_TEMPLATE_SNAPSHOTS, "The snapshots, in the order of the images; empty when there are none.");
        setValue(J_L.DESC_TEMPLATE_REFERENCES, "How many images of the user follow the snapshots; empty when none.");
        setValue(J_L.DESC_TEMPLATE_BODY_NOTES, "The user's notes on the model.");
        setValue(J_L.DESC_TEMPLATE_CURRENT_DESCRIPTION, "The description the body has now; empty when none.");
        setValue(J_L.DESC_TEMPLATE_TOGGLES, "A line per toggle: its number, the mesh / key, its name now, shown or hidden as the model comes.");
        setValue(J_L.DESC_TEMPLATE_VOICE, "How her voice sounds (the description of her voice actor)");
    }
}
