package com.aurevia.staffmsg.attachments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.staffmsg.instant.InstantConversationStore;
import com.aurevia.staffmsg.instant.InstantMessagingService;
import com.aurevia.staffmsg.mail.MailService;
import com.aurevia.staffmsg.mail.MailStore;

/**
 * Proves that Spring constructs the single real publication bean and injects it
 * into both message-send services (MSG-03, ARC-07). The context is limited to the
 * relevant beans and uses synthetic collaborators, so no database is started.
 */
class StaffAttachmentPublicationWiringTest {

    /** The production constructors must receive the real publisher, not an inert fallback. */
    @Test
    void bothSendServicesReceiveTheSinglePublisherBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(StaffAttachmentConfiguration.class, StaffAttachmentService.class,
                        MailService.class, InstantMessagingService.class)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC))
                .withBean(StaffRepository.class, () -> mock(StaffRepository.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                .withBean(StaffDirectoryService.class, () -> mock(StaffDirectoryService.class))
                .withBean(AccessRepository.class, () -> mock(AccessRepository.class))
                .withBean(DirectoryPolicy.class, DirectoryPolicy::new)
                .withBean(MailStore.class, () -> mock(MailStore.class))
                .withBean(InstantConversationStore.class, () -> mock(InstantConversationStore.class))
                .withBean(StaffAttachmentStore.class, InMemoryStaffAttachmentStore::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StaffAttachmentPublication.class);
                    StaffAttachmentPublication publication = context.getBean(StaffAttachmentPublication.class);
                    assertThat(publication).isInstanceOf(StaffAttachmentPublicationService.class);
                    assertThat(injected(context.getBean(MailService.class), "attachments")).isSameAs(publication);
                    assertThat(injected(context.getBean(InstantMessagingService.class), "attachments"))
                            .isSameAs(publication);
                });
    }

    private static Object injected(Object bean, String fieldName) {
        try {
            Field field = bean.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(bean);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot read injected publication of " + bean.getClass(), failure);
        }
    }
}
