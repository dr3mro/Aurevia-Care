import { useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { ApiPage } from '../../components/ApiPage';
import { useI18n } from '../../i18n/I18nProvider';
import { getInbox, getWorkQueue } from './api';
import { MessagesScreen } from './MessagesScreen';
import { messagesText } from './messages';
import type { Inbox, MessageTab, WorkQueue } from './types';

/** Both projections are read together so the active tab needs no refetch. */
interface TabData {
  inbox: Inbox;
  workQueue: WorkQueue;
}

/** API-backed unit inbox with Inbox and Work queue tabs. */
export function MessagesPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const [tab, setTab] = useState<MessageTab>('inbox');

  return (
    <div>
      <h2>{messagesText(locale, 'messages')}</h2>
      <p>{messagesText(locale, 'messagesIntro')}</p>
      <p>{messagesText(locale, 'syntheticNotice')}</p>
      <ApiPage
        resource={{
          label: messagesText(locale, 'messages'),
          load: async () => {
            const [inbox, workQueue] = await Promise.all([
              getInbox(organizationId),
              getWorkQueue(organizationId),
            ]);
            return { inbox, workQueue };
          },
          deps: [organizationId],
        }}
        isEmpty={(data: TabData) =>
          tab === 'inbox'
            ? data.inbox.entries.length === 0
            : data.workQueue.entries.length === 0
        }
      >
        {(data) => (
          <MessagesScreen
            inbox={data.inbox}
            workQueue={data.workQueue}
            tab={tab}
            organizationId={organizationId}
            nowMs={Date.now()}
            onTabChange={setTab}
          />
        )}
      </ApiPage>
    </div>
  );
}
