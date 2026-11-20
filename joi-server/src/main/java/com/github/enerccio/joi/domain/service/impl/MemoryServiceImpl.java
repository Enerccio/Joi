package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.joi.domain.collections.MemoryImportance;
import com.github.enerccio.joi.domain.model.impl.Joi;
import com.github.enerccio.joi.domain.model.impl.Memory;
import com.github.enerccio.joi.domain.repository.MemoryRepository;
import com.github.enerccio.joi.domain.service.MemoryService;
import com.github.enerccio.marginalia.domain.service.impl.ExtendableServiceImpl;
import com.github.enerccio.marginalia.domain.traits.CommonTx;
import com.github.enerccio.marginalia.domain.traits.CommonTxReadOnly;

import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

public class MemoryServiceImpl extends ExtendableServiceImpl<Memory, MemoryRepository> implements MemoryService {

    @Override
    @CommonTxReadOnly
    public List<Memory> findByImportance(Joi joi, MemoryImportance importance) throws Exception {
        return getRepository().findByImportance(joi, importance);
    }

    @Override
    @CommonTxReadOnly
    public List<Memory> findAll(Joi joi) throws Exception {
        return getRepository().findAll(joi);
    }

    @Override
    @CommonTx
    public Memory create(Joi joi) throws Exception {
        Memory memory = new Memory();
        memory.setJoi(joi);
        memory.setImportance(MemoryImportance.MEDIUM);
        memory.setApproved(true);
        return save(memory);
    }

    @Override
    @CommonTx
    public Memory update(Joi joi, Memory memory) throws Exception {
        Memory stored = hers(joi, memory.getId());
        if (stored == null) {
            return null;
        }
        stored.setName(memory.getName());
        stored.setImportance(memory.getImportance() == null ? MemoryImportance.MEDIUM : memory.getImportance());
        stored.setKeywords(memory.getKeywords());
        stored.setText(memory.getText());
        return save(stored);
    }

    @Override
    @CommonTx
    public Memory remember(Joi joi, String name, String text, String keywords, MemoryImportance importance) throws Exception {
        Memory memory = new Memory();
        memory.setJoi(joi);
        memory.setImportance(importance == null ? MemoryImportance.MEDIUM : importance);
        memory.setApproved(false);
        memory.setName(StringUtils.abbreviate(StringUtils.defaultIfBlank(name, text), 60));
        memory.setText(text.strip());
        memory.setKeywords(normaliseKeywords(keywords));
        return save(memory);
    }

    @Override
    @CommonTxReadOnly
    public List<Memory> search(Joi joi, String query, int limit) throws Exception {
        Set<String> words = words(query);
        if (words.isEmpty()) {
            return List.of();
        }
        List<Memory> found = new ArrayList<>();
        Map<Memory, Integer> scores = new HashMap<>();
        for (Memory memory : getRepository().findAll(joi)) {
            String keywords = StringUtils.defaultString(memory.getKeywords()).toLowerCase(Locale.ROOT);
            String rest = (StringUtils.defaultString(memory.getName()) + " " + StringUtils.defaultString(memory.getText())).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String word : words) {
                score += keywords.contains(word) ? 3 : 0;
                score += rest.contains(word) ? 1 : 0;
            }
            if (score > 0) {
                scores.put(memory, score);
                found.add(memory);
            }
        }
        // the best first, the newest of the same score first (the list is newest first, the sort is stable)
        found.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        return found.size() > limit ? List.copyOf(found.subList(0, Math.max(limit, 0))) : found;
    }

    @Override
    @CommonTxReadOnly
    public String prompt(Joi joi, String said, int limit) throws Exception {
        String lower = StringUtils.defaultString(said).toLowerCase(Locale.ROOT);
        List<Memory> chosen = new ArrayList<>();
        for (Memory memory : getRepository().findAll(joi)) {
            if (StringUtils.isBlank(memory.getText())) {
                continue;
            }
            boolean important = memory.getImportance() == MemoryImportance.IMPORTANT;
            boolean keyed = !lower.isBlank() && keywordList(memory.getKeywords()).stream().anyMatch(lower::contains);
            if (important || keyed) {
                chosen.add(memory);
            }
        }
        // important ones first, then by recency (the list is newest first)
        chosen.sort((a, b) -> Boolean.compare(b.getImportance() == MemoryImportance.IMPORTANT, a.getImportance() == MemoryImportance.IMPORTANT));
        if (chosen.isEmpty()) {
            return null;
        }
        return chosen.stream().limit(limit).map(memory -> "- " + memory.getText().strip().replaceAll("\\s*\\R\\s*", " "))
                .collect(Collectors.joining("\n"));
    }

    /** The keywords as one list, comma separated, each trimmed, lower case and once. */
    static String normaliseKeywords(String keywords) {
        return String.join(", ", keywordList(keywords));
    }

    private static List<String> keywordList(String keywords) {
        if (StringUtils.isBlank(keywords)) {
            return List.of();
        }
        return Arrays.stream(keywords.split("[,;\\n]")).map(k -> k.strip().toLowerCase(Locale.ROOT))
                .filter(k -> !k.isEmpty()).distinct().toList();
    }

    /** The words of a query worth looking for: three letters or more. */
    private static Set<String> words(String query) {
        if (StringUtils.isBlank(query)) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    @Override
    @CommonTx
    public int deleteMemories(Joi joi, Collection<Long> ids) throws Exception {
        int deleted = 0;
        for (Long id : ids) {
            Memory memory = hers(joi, id);
            if (memory != null) {
                delete(memory, false);
                deleted++;
            }
        }
        return deleted;
    }

    /** The memory when it is one of hers and not deleted, else null. */
    private Memory hers(Joi joi, Long id) throws Exception {
        Memory memory = id == null ? null : find(id);
        if (memory == null || memory.isDeleted() || memory.getJoi() == null || !memory.getJoi().getId().equals(joi.getId())) {
            return null;
        }
        return memory;
    }
}
