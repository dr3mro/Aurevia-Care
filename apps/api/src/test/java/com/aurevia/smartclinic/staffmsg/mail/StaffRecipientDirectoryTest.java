package com.aurevia.smartclinic.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.staff.StaffMember;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staff.StaffRepository.StaffRow;

/** Production role-holder expansion over the authoritative staff projection. */
class StaffRecipientDirectoryTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void expandsOnlyActiveHoldersOfTheRoleAndDeduplicatesThem() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(
                        new StaffRow(member("nurse-a", "reception"), "id-a"),
                        new StaffRow(member("nurse-b", "nurse"), "id-b"),
                        new StaffRow(member("nurse-b", "nurse"), "id-b-2"),
                        new StaffRow(member("doctor-a", "nurse"), "id-c")));

        assertEquals(List.of("nurse-b", "doctor-a"), new StaffRecipientDirectory(staff, transactions)
                .holders(ORG, FACILITY, "nurse"));
    }

    @Test
    void returnsAnEmptyListWhenNobodyHoldsTheRole() {
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.query(any(), any(), any(), any(Integer.class))).thenReturn(List.of());

        assertEquals(List.of(), new StaffRecipientDirectory(staff, transactions).holders(ORG, FACILITY, "nurse"));
    }

    private static StaffMember member(String subject, String role) {
        return new StaffMember(subject, subject, "active", List.of(role));
    }
}
