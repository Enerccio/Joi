package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.defaults.JoiDefaults;
import com.github.enerccio.joi.defaults.MoodDefault;
import com.github.enerccio.joi.defaults.PersonalityDefault;
import com.github.enerccio.joi.domain.model.impl.settings.JoiUserSetting;
import com.github.enerccio.joi.domain.persona.*;
import com.github.enerccio.joi.domain.service.PersonaService;
import com.github.enerccio.joi.domain.templates.ChatTemplateData;
import com.github.enerccio.joi.domain.templates.JoiTemplateData;
import com.github.enerccio.joi.domain.templates.PartRequestTemplateData;
import com.github.enerccio.marginalia.domain.security.model.User;
import com.github.enerccio.marginalia.domain.service.SettingService;
import com.github.enerccio.marginalia.domain.service.TemplateService;
import com.github.enerccio.marginalia.loc.Language;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.Collator;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * {@link PersonaService} on the user setting ({@link JoiUserSetting}) and the platform's {@link TemplateService}.
 */
public class PersonaServiceImpl implements PersonaService {

    @Autowired
    private SettingService settingService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private User currentUser;

    @Override
    public JoiUserSetting getUserSetting() throws Exception {
        return (JoiUserSetting) settingService.getUserSetting();
    }

    @Override
    public JoiDefaults defaults() throws Exception {
        String code = getUserSetting().getPromptLanguage();
        return JoiDefaults.forLanguage(StringUtils.isBlank(code) ? Language.EN : Language.of(code));
    }

    @Override
    public String assistantName() throws Exception {
        String name = getUserSetting().getAssistantName();
        return StringUtils.isBlank(name) ? JoiDefaults.ASSISTANT_NAME : name.strip();
    }

    @Override
    public String userName() throws Exception {
        // the chain: the user's setting, else the account (assistants may add their own link later)
        String name = getUserSetting().getUserName();
        if (StringUtils.isNotBlank(name)) {
            return name.strip();
        }
        return accountName();
    }

    @Override
    public String accountName() {
        return StringUtils.isNotBlank(currentUser.getFullName()) ? currentUser.getFullName() : StringUtils.defaultString(currentUser.getLogin());
    }

    @Override
    public String resolve(JoiPromptField field) throws Exception {
        return field.resolve((String) null, getUserSetting(), defaults());
    }

    // ----------------------------------------------------------------------------------------------------------------
    // personalities
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public List<Personality> personalities() throws Exception {
        JoiUserSetting setting = getUserSetting();
        Map<String, PersonalitySetting> own = setting.getPersonalities().stream()
                .filter(p -> StringUtils.isNotBlank(p.getKey()))
                .collect(Collectors.toMap(PersonalitySetting::getKey, p -> p, (a, b) -> b, LinkedHashMap::new));
        List<Personality> result = new ArrayList<>();
        for (PersonalityDefault builtIn : defaults().personalities()) {
            PersonalitySetting changes = own.remove(builtIn.key());
            Map<String, String> expressions = new LinkedHashMap<>(builtIn.moodExpressions());
            boolean changed = false;
            String name = builtIn.name();
            String prompt = builtIn.prompt();
            String partConsent = builtIn.partConsent();
            if (changes != null) {
                changes.getMoodExpressions().forEach((mood, text) -> {
                    if (StringUtils.isNotBlank(text)) {
                        expressions.put(mood, text);
                    }
                });
                changed = StringUtils.isNotBlank(changes.getName()) || StringUtils.isNotBlank(changes.getPrompt())
                        || StringUtils.isNotBlank(changes.getPartConsent())
                        || changes.getMoodExpressions().values().stream().anyMatch(StringUtils::isNotBlank);
                name = StringUtils.defaultIfBlank(changes.getName(), name);
                prompt = StringUtils.defaultIfBlank(changes.getPrompt(), prompt);
                partConsent = StringUtils.defaultIfBlank(changes.getPartConsent(), partConsent);
            }
            result.add(new Personality(builtIn.key(), name, prompt, Map.copyOf(expressions), partConsent, true, changed));
        }
        List<Personality> custom = new ArrayList<>();
        for (PersonalitySetting mine : own.values()) {
            Map<String, String> expressions = new LinkedHashMap<>();
            mine.getMoodExpressions().forEach((mood, text) -> {
                if (StringUtils.isNotBlank(text)) {
                    expressions.put(mood, text);
                }
            });
            custom.add(new Personality(mine.getKey(), StringUtils.defaultIfBlank(mine.getName(), mine.getKey()),
                    StringUtils.defaultString(mine.getPrompt()), Map.copyOf(expressions), mine.getPartConsent(), false, false));
        }
        custom.sort(Comparator.comparing(Personality::name, collator()));
        result.addAll(custom);
        return result;
    }

    @Override
    public Personality personality(String key) throws Exception {
        return key == null ? null : personalities().stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    @Override
    public Personality personalityOf(Joi joi) throws Exception {
        Personality own = joi == null ? null : personality(joi.getPersonality());
        return own != null ? own : currentPersonality();
    }

    @Override
    public String renderPartRequests(Joi joi, String requests, String events) throws Exception {
        Personality personality = personalityOf(joi);
        if (personality == null || !personality.decidesOnParts()) {
            return null;
        }
        PartRequestTemplateData data = new PartRequestTemplateData();
        data.setRequests(requests);
        data.setEvents(StringUtils.defaultIfBlank(events, null));
        String name = StringUtils.isNotBlank(joi.getAssistantName()) ? joi.getAssistantName().strip() : assistantName();
        String user = StringUtils.isNotBlank(joi.getUserName()) ? joi.getUserName().strip() : userName();
        data.names(name, user);
        // her personality's words may use the names too ({{user}}), so they are a template of their own
        data.setConsent(fragment(personality.partConsent(), "partConsent", data));
        return render(joi, JoiPromptField.PART_REQUEST_EVENT, data);
    }

    @Override
    public Personality currentPersonality() throws Exception {
        Personality chosen = personality(getUserSetting().getPersonality());
        return chosen != null ? chosen : personality(JoiDefaults.DEFAULT_PERSONALITY);
    }

    @Override
    public void savePersonality(PersonalitySetting personality) throws Exception {
        if (StringUtils.isBlank(personality.getKey())) {
            throw new IllegalArgumentException("A personality needs a key");
        }
        JoiUserSetting setting = getUserSetting();
        List<PersonalitySetting> list = new ArrayList<>(setting.getPersonalities());
        list.removeIf(p -> personality.getKey().equals(p.getKey()));
        list.add(personality);
        setting.setPersonalities(list);
        settingService.save(setting);
    }

    @Override
    public String clonePersonality(String key, String name) throws Exception {
        Personality source = personality(key);
        if (source == null) {
            throw new IllegalArgumentException("No personality " + key);
        }
        Set<String> taken = personalities().stream().map(Personality::key).collect(Collectors.toSet());
        String base = key.replaceFirst("-copy(-\\d+)?$", "") + "-copy";
        String copyKey = base;
        for (int n = 2; taken.contains(copyKey); n++) {
            copyKey = base + "-" + n;
        }
        // as it is now: the copy is the user's own, it has no built-in text to fall back on
        PersonalitySetting copy = new PersonalitySetting(copyKey);
        copy.setName(StringUtils.defaultIfBlank(name, source.name()));
        copy.setPrompt(source.prompt());
        copy.setPartConsent(source.partConsent());
        copy.setMoodExpressions(source.moodExpressions());
        savePersonality(copy);
        return copyKey;
    }

    @Override
    public void resetPersonality(String key) throws Exception {
        JoiUserSetting setting = getUserSetting();
        List<PersonalitySetting> list = new ArrayList<>(setting.getPersonalities());
        list.removeIf(p -> key.equals(p.getKey()));
        setting.setPersonalities(list);
        if (key.equals(setting.getPersonality()) && defaults().personalities().stream().noneMatch(p -> p.key().equals(key))) {
            setting.setPersonality(null);
        }
        settingService.save(setting);
    }

    // ----------------------------------------------------------------------------------------------------------------
    // moods
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public List<Mood> moods() throws Exception {
        Map<String, MoodSetting> own = getUserSetting().getMoods().stream()
                .filter(m -> StringUtils.isNotBlank(m.getKey()))
                .collect(Collectors.toMap(MoodSetting::getKey, m -> m, (a, b) -> b, LinkedHashMap::new));
        List<Mood> result = new ArrayList<>();
        for (MoodDefault builtIn : defaults().moods()) {
            MoodSetting changes = own.remove(builtIn.key());
            if (changes == null) {
                result.add(new Mood(builtIn.key(), builtIn.name(), builtIn.prompt(), builtIn.weight(), builtIn.delivery(), builtIn.face(), true, false));
                continue;
            }
            boolean changed = StringUtils.isNotBlank(changes.getName()) || StringUtils.isNotBlank(changes.getPrompt())
                    || changes.getWeight() != null || changes.getDelivery() != null || MoodFace.changes(changes);
            result.add(new Mood(builtIn.key(), StringUtils.defaultIfBlank(changes.getName(), builtIn.name()),
                    StringUtils.defaultIfBlank(changes.getPrompt(), builtIn.prompt()),
                    changes.getWeight() == null ? builtIn.weight() : Math.max(0, changes.getWeight()),
                    changes.getDelivery() == null ? builtIn.delivery() : changes.getDelivery(), builtIn.face().changedBy(changes), true, changed));
        }
        List<Mood> custom = new ArrayList<>();
        for (MoodSetting mine : own.values()) {
            custom.add(new Mood(mine.getKey(), StringUtils.defaultIfBlank(mine.getName(), mine.getKey()),
                    StringUtils.defaultString(mine.getPrompt()), mine.getWeight() == null ? 1 : Math.max(0, mine.getWeight()),
                    mine.getDelivery() == null ? com.github.enerccio.joi.tts.text.Delivery.NEUTRAL : mine.getDelivery(),
                    MoodFace.PLAIN.changedBy(mine), false, false));
        }
        custom.sort(Comparator.comparing(Mood::name, collator()));
        result.addAll(custom);
        return result;
    }

    @Override
    public Mood mood(String key) throws Exception {
        return key == null ? null : moods().stream().filter(m -> m.key().equals(key)).findFirst().orElse(null);
    }

    @Override
    public void saveMood(MoodSetting mood) throws Exception {
        if (StringUtils.isBlank(mood.getKey())) {
            throw new IllegalArgumentException("A mood needs a key");
        }
        JoiUserSetting setting = getUserSetting();
        List<MoodSetting> list = new ArrayList<>(setting.getMoods());
        list.removeIf(m -> mood.getKey().equals(m.getKey()));
        list.add(mood);
        setting.setMoods(list);
        settingService.save(setting);
    }

    @Override
    public void resetMood(String key) throws Exception {
        JoiUserSetting setting = getUserSetting();
        List<MoodSetting> list = new ArrayList<>(setting.getMoods());
        list.removeIf(m -> key.equals(m.getKey()));
        setting.setMoods(list);
        settingService.save(setting);
    }

    @Override
    public String moodText(Personality personality, Mood mood) {
        if (mood == null) {
            return null;
        }
        String expression = personality == null ? null : personality.moodExpressions().get(mood.key());
        return StringUtils.isNotBlank(expression) ? expression : mood.prompt();
    }

    @Override
    public Mood rollMood(RandomGenerator random) throws Exception {
        List<Mood> moods = moods();
        int total = moods.stream().mapToInt(Mood::weight).sum();
        if (total <= 0) {
            Mood fallback = mood(JoiDefaults.DEFAULT_MOOD);
            return fallback != null ? fallback : moods.getFirst();
        }
        int pick = random.nextInt(total);
        for (Mood mood : moods) {
            pick -= mood.weight();
            if (pick < 0) {
                return mood;
            }
        }
        return moods.getLast();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // rendering
    // ----------------------------------------------------------------------------------------------------------------

    @Override
    public String render(JoiPromptField field, JoiTemplateData data) throws Exception {
        data.names(assistantName(), userName());
        return tidy(templateService.processTemplate(resolve(field), field.templateName(), data));
    }

    @Override
    public String render(Joi joi, JoiPromptField field, JoiTemplateData data) throws Exception {
        String name = StringUtils.isNotBlank(joi.getAssistantName()) ? joi.getAssistantName().strip() : assistantName();
        String user = StringUtils.isNotBlank(joi.getUserName()) ? joi.getUserName().strip() : userName();
        data.names(name, user);
        return tidy(templateService.processTemplate(field.resolve(joi, getUserSetting(), defaults()), field.templateName(), data));
    }

    @Override
    public String renderChatSystemPrompt(String moodKey, ChatTemplateData data) throws Exception {
        return renderChatSystemPrompt(null, moodKey, data);
    }

    @Override
    public String renderChatSystemPrompt(String template, String moodKey, ChatTemplateData data) throws Exception {
        return renderChatSystemPrompt(null, template, moodKey, data);
    }

    @Override
    public String renderChatSystemPrompt(Joi joi, String template, String moodKey, ChatTemplateData data) throws Exception {
        String name = joi != null && StringUtils.isNotBlank(joi.getAssistantName()) ? joi.getAssistantName().strip() : assistantName();
        String user = joi != null && StringUtils.isNotBlank(joi.getUserName()) ? joi.getUserName().strip() : userName();
        data.names(name, user);
        Personality personality = joi == null ? null : personality(joi.getPersonality());
        if (personality == null) {
            personality = currentPersonality();
        }
        Mood mood = mood(moodKey);
        if (mood == null) {
            mood = mood(JoiDefaults.DEFAULT_MOOD);
        }
        // the fragments may use the names too ({{user}}), so they are templates of their own
        data.setPersonalityPrompt(personality == null ? null : fragment(personality.prompt(), "personality", data));
        data.setMood(fragment(moodText(personality, mood), "mood", data));
        String master = StringUtils.isNotBlank(template) ? template
                : JoiPromptField.MASTER_TEMPLATE.resolve(joi, getUserSetting(), defaults());
        return tidy(templateService.processTemplate(master, JoiPromptField.MASTER_TEMPLATE.templateName(), data));
    }

    private String fragment(String text, String name, JoiTemplateData data) throws Exception {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        ChatTemplateData names = new ChatTemplateData();
        names.setTemplateContext(data.templateContext());
        names.names(data.character(), data.user());
        return templateService.processTemplate(text, name, names).strip();
    }

    /**
     * The rendered text with at most one blank line in a row: a section left out (no memories) or the line of its tag
     * would otherwise leave a gap.
     */
    static String tidy(String text) {
        return text == null ? null : text.replaceAll("\\n[ \\t]*(\\n[ \\t]*){2,}", "\n\n").strip();
    }

        private static Collator collator() {
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        return collator;
    }
}
