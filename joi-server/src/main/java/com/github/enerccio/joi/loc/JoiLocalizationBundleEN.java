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

        // --- ENUM ---
        setValue(J_L.ENUM_TEXT_LLM_TYPE_AUXILIARY, "Auxiliary");
        setValue(J_L.ENUM_TEXT_LLM_TYPE_CHAT, "Chat");
        setValue(J_L.ENUM_TEXT_LLM_TYPE_DEEP_REASONING, "Deep reasoning");
        setValue(J_L.ENUM_TTS_MODEL_TURBO, "Chatterbox Turbo (English, sound tags)");
        setValue(J_L.ENUM_TTS_MODEL_ORIGINAL, "Chatterbox (English, exaggeration)");
        setValue(J_L.ENUM_TTS_MODEL_MULTILINGUAL, "Chatterbox Multilingual (23 languages)");
        setValue(J_L.ENUM_TTS_MODEL_HIGGS, "Higgs Audio v3 in ComfyUI (emotions, sounds; non-commercial license)");

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
    }
}
