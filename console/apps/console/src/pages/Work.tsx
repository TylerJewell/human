import { Chip, EventPage, Queue, useEvent } from "@akka-human/kit";
import { useServices } from "../api";

// The queue across every service, and one event's page with the service that owns it.

export function QueuePage({ go }: { go: (path: string) => void }) {
  const services = useServices();
  return (
    <Queue
      title="Events across every service"
      services={(services.data ?? []).map((s) => s.service)}
      onOpen={(row) => go(`/events/${encodeURIComponent(row.id)}`)}
    />
  );
}

export function EventRoute({ id }: { id: string }) {
  const event = useEvent(id);
  const services = useServices();
  const owner = event.data?.owner.service;
  const service = services.data?.find((s) => s.service === owner);
  return (
    <div className="hk-stack" data-testid="event-route">
      <nav className="cs-crumbs" aria-label="Where you are">
        <a href="#/queue">Queue</a>
        <span aria-hidden="true">›</span>
        {owner ? (
          <span className="cs-owner" data-testid="event-owner">
            Owned by <strong>{owner}</strong>
            {service ? (
              <Chip tone={service.up ? "success" : "danger"}>{service.up ? "Answers" : "Does not answer"}</Chip>
            ) : null}
            {service ? <span className="hk-muted cs-mono">{service.url}</span> : null}
          </span>
        ) : (
          <span className="hk-muted">{id}</span>
        )}
      </nav>
      <EventPage eventId={id} />
    </div>
  );
}
