package com.aurevia.smartclinic.staffmsg.instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Deterministic in-memory fake of the instant-conversation persistence port.
 *
 * <p>Test-only. Mutations are synchronized so a parallel test cannot create a
 * duplicate sequence or a duplicate idempotency key. The fake keeps membership
 * history rows and per-reader read cursors exactly as a durable implementation
 * would, but it does not survive a process restart.</p>
 */
final class InMemoryInstantConversationState implements InstantConversationState {

    private final Map<String, StaffConversation> conversations = new LinkedHashMap<>();
    private final Map<String, Map<String, StaffParticipant>> participants = new LinkedHashMap<>();
    private final Map<String, List<StaffMessage>> messages = new LinkedHashMap<>();
    private final Map<String, StaffMessage> byClientKey = new LinkedHashMap<>();
    private final Map<String, Long> readCursors = new LinkedHashMap<>();
    private final Map<String, String> directIndex = new LinkedHashMap<>();

    @Override
    public synchronized Optional<StaffConversation> findDirect(String organizationId, String facilityId,
                                                               String first, String second) {
        String id = directIndex.get(key(organizationId, facilityId, first, second));
        return id == null ? Optional.empty() : Optional.ofNullable(conversations.get(key(organizationId, id)));
    }

    @Override
    public synchronized Optional<StaffConversation> findConversation(String organizationId,
                                                                     String conversationId) {
        return Optional.ofNullable(conversations.get(key(organizationId, conversationId)));
    }

    @Override
    public synchronized void saveConversation(StaffConversation conversation, List<String> members) {
        conversations.put(key(conversation.organizationId(), conversation.id()), conversation);
        participants.put(key(conversation.organizationId(), conversation.id()), new LinkedHashMap<>());
        messages.put(key(conversation.organizationId(), conversation.id()), new ArrayList<>());
        if (conversation.kind().wire().equals("direct") && members.size() == 2) {
            String first = members.get(0).compareTo(members.get(1)) < 0 ? members.get(0) : members.get(1);
            String second = first.equals(members.get(0)) ? members.get(1) : members.get(0);
            directIndex.put(key(conversation.organizationId(), conversation.facilityId(), first, second),
                    conversation.id());
        }
    }

    @Override
    public synchronized Optional<StaffParticipant> participant(String organizationId, String conversationId,
                                                               String subject) {
        return Optional.ofNullable(participants.getOrDefault(key(organizationId, conversationId),
                Map.of()).get(subject));
    }

    @Override
    public synchronized List<StaffParticipant> participants(String organizationId, String conversationId) {
        return List.copyOf(participants.getOrDefault(key(organizationId, conversationId),
                Map.of()).values());
    }

    @Override
    public synchronized void saveParticipant(StaffParticipant participant) {
        participants.computeIfAbsent(key(participant.organizationId(), participant.conversationId()),
                ignored -> new LinkedHashMap<>()).put(participant.subject(), participant);
    }

    @Override
    public synchronized Optional<StaffMessage> messageByClientId(String organizationId,
                                                                 String conversationId,
                                                                 String clientMessageId) {
        return Optional.ofNullable(byClientKey.get(key(organizationId, conversationId, clientMessageId)));
    }

    @Override
    public synchronized void saveMessage(StaffMessage message, String clientMessageId) {
        String conversationKey = key(message.organizationId(), message.conversationId());
        messages.computeIfAbsent(conversationKey, ignored -> new ArrayList<>()).add(message);
        byClientKey.put(key(message.organizationId(), message.conversationId(), clientMessageId), message);
    }

    @Override
    public synchronized List<StaffMessage> messages(String organizationId, String conversationId) {
        return List.copyOf(messages.getOrDefault(key(organizationId, conversationId), List.of()));
    }

    @Override
    public synchronized long nextSequence(String organizationId, String conversationId) {
        return lastSequence(organizationId, conversationId) + 1L;
    }

    @Override
    public synchronized long lastSequence(String organizationId, String conversationId) {
        return messages.getOrDefault(key(organizationId, conversationId), List.of()).stream()
                .mapToLong(StaffMessage::sequence).max().orElse(0L);
    }

    @Override
    public synchronized long lastReadSequence(String organizationId, String conversationId,
                                              String subject) {
        return readCursors.getOrDefault(key(organizationId, conversationId, subject), 0L);
    }

    @Override
    public synchronized void saveReadSequence(String organizationId, String conversationId,
                                              String subject, long sequence) {
        String cursorKey = key(organizationId, conversationId, subject);
        long existing = readCursors.getOrDefault(cursorKey, 0L);
        readCursors.put(cursorKey, Math.max(existing, sequence));
    }

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }
}
