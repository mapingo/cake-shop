package uk.gov.justice.services.cakeshop.it;

import static java.util.Optional.of;
import static javax.json.Json.createObjectBuilder;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static uk.gov.justice.services.cakeshop.it.helpers.TestConstants.CONTEXT_NAME;
import static uk.gov.justice.services.cakeshop.it.helpers.TestConstants.DB_CONTEXT_NAME;
import static uk.gov.justice.services.jmx.api.domain.CommandState.COMMAND_COMPLETE;
import static uk.gov.justice.services.jmx.api.domain.CommandState.COMMAND_FAILED;
import static uk.gov.justice.services.jmx.api.mbean.CommandRunMode.GUARDED;
import static uk.gov.justice.services.jmx.api.parameters.JmxCommandRuntimeParameters.withNoCommandParameters;

import uk.gov.justice.services.cakeshop.it.helpers.BatchEventInserter;
import uk.gov.justice.services.cakeshop.it.helpers.CakeshopEventGenerator;
import uk.gov.justice.services.cakeshop.it.helpers.DatabaseManager;
import uk.gov.justice.services.cakeshop.it.helpers.JmxParametersFactory;
import uk.gov.justice.services.cakeshop.it.helpers.PositionInStreamIterator;
import uk.gov.justice.services.cakeshop.it.helpers.PublishedEventCounter;
import uk.gov.justice.services.eventsourcing.repository.jdbc.event.Event;
import uk.gov.justice.services.eventstore.management.commands.ValidatePublishedEventsCommand;
import uk.gov.justice.services.jmx.api.command.SystemCommand;
import uk.gov.justice.services.jmx.api.domain.CommandState;
import uk.gov.justice.services.jmx.api.domain.SystemCommandStatus;
import uk.gov.justice.services.jmx.api.mbean.SystemCommanderMBean;
import uk.gov.justice.services.jmx.api.parameters.JmxCommandRuntimeParameters;
import uk.gov.justice.services.jmx.system.command.client.SystemCommanderClient;
import uk.gov.justice.services.jmx.system.command.client.TestSystemCommanderClientFactory;
import uk.gov.justice.services.jmx.system.command.client.connection.JmxParameters;
import uk.gov.justice.services.test.utils.core.messaging.Poller;
import uk.gov.justice.services.test.utils.persistence.DatabaseCleaner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EventValidationIT {

    private static final int BATCH_INSERT_SIZE = 10_000;

    private final DataSource eventStoreDataSource = new DatabaseManager().initEventStoreDb();

    @SuppressWarnings("unused")
    private final DataSource viewStoreDataSource = new DatabaseManager().initViewStoreDb();
    private final DatabaseCleaner databaseCleaner = new DatabaseCleaner();

    private final TestSystemCommanderClientFactory systemCommanderClientFactory = new TestSystemCommanderClientFactory();

    private final Poller poller = new Poller(100, 1000);
    private final BatchEventInserter batchEventInserter = new BatchEventInserter(eventStoreDataSource, BATCH_INSERT_SIZE);

    private final PublishedEventCounter publishedEventCounter = new PublishedEventCounter(eventStoreDataSource);

    private final JmxParameters jmxParameters = JmxParametersFactory.buildJmxParameters();

    @BeforeEach
    public void cleanTables() {
        databaseCleaner.cleanEventStoreTables(DB_CONTEXT_NAME);
        databaseCleaner.cleanProcessedEventTable(DB_CONTEXT_NAME);
        databaseCleaner.cleanStreamStatusTable(DB_CONTEXT_NAME);
        databaseCleaner.cleanStreamBufferTable(DB_CONTEXT_NAME);
        databaseCleaner.cleanViewStoreTables(DB_CONTEXT_NAME, "recipe");
        databaseCleaner.cleanSystemTables(DB_CONTEXT_NAME);
    }

    @Test
    public void shouldRunValidateEventsCommand() throws Exception {

        final int numberOfStreams = 2;
        final int numberOfEventsPerStream = 3;

        final int totalEvents = numberOfEventsPerStream * numberOfStreams;

        addEventsToEventLog(numberOfStreams, numberOfEventsPerStream);

        waitForEventsToPublish(totalEvents);

        final Optional<SystemCommandStatus> systemCommandStatus = runCommand(jmxParameters, new ValidatePublishedEventsCommand());

        if (systemCommandStatus.isPresent()) {
            assertThat(systemCommandStatus.get().getMessage(), is("All PublishedEvents successfully passed schema validation"));
        } else {
            fail();
        }

    }

    @Test
    public void shouldFailIfAnyEventsAreInvalid() throws Exception {

        final int numberOfStreams = 2;
        final int numberOfEventsPerStream = 3;

        final int totalEvents = numberOfEventsPerStream * numberOfStreams;

        addEventsToEventLog(numberOfStreams, numberOfEventsPerStream);

        waitForEventsToPublish(totalEvents);

        setPayloadsOfEventsInvalid();

        final Optional<SystemCommandStatus> systemCommandStatus = runCommand(jmxParameters, new ValidatePublishedEventsCommand());

        if (systemCommandStatus.isPresent()) {
            assertThat(systemCommandStatus.get().getMessage(), is("6 PublishedEvent(s) failed schema validation. Please see server logs for errors"));
        } else {
            fail();
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void waitForEventsToPublish(final int totalEvents) {
        final Optional<Integer> publishedEventCount = poller.pollUntilFound(() -> {
            final int eventCount = publishedEventCounter.countPublishedEvents();
            System.out.printf("Polling published_event table. Expected events count: %d, found: %d\n", totalEvents, eventCount);
            if (eventCount == totalEvents) {
                return of(eventCount);
            }

            return Optional.empty();
        });

        if (! publishedEventCount.isPresent()) {
            assertThat("Incorrect number of events were published", publishedEventCounter.countPublishedEvents(), is(totalEvents));
        }
    }

    private Optional<SystemCommandStatus> runCommand(final JmxParameters jmxParameters, final SystemCommand systemCommand) {
        try (final SystemCommanderClient systemCommanderClient = systemCommanderClientFactory.create(jmxParameters)) {

            final SystemCommanderMBean systemCommanderMBean = systemCommanderClient.getRemote(CONTEXT_NAME);
            final JmxCommandRuntimeParameters jmxCommandRuntimeParameters = withNoCommandParameters();
            final UUID commandId = systemCommanderMBean.call(
                    systemCommand.getName(),
                    jmxCommandRuntimeParameters.getCommandRuntimeId(),
                    jmxCommandRuntimeParameters.getCommandRuntimeString(),
                    GUARDED.isGuarded());

            return poller.pollUntilFound(() -> commandNoLongerInProgress(systemCommanderMBean, commandId));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void addEventsToEventLog(final int numberOfStreams, final int numberOfEventsPerStream) throws Exception {

        final CakeshopEventGenerator cakeshopEventGenerator = new CakeshopEventGenerator();

        final List<Event> events = new ArrayList<>();
        final List<UUID> streamIds = new ArrayList<>();

        for (int seed = 0; seed < numberOfStreams; seed++) {

            final PositionInStreamIterator positionInStreamIterator = new PositionInStreamIterator();

            final Event recipeAddedEvent = cakeshopEventGenerator.createRecipeAddedEvent(seed, positionInStreamIterator);
            final UUID recipeId = recipeAddedEvent.getStreamId();

            if (!streamIds.contains(recipeId)) {
                streamIds.add(recipeId);
            }

            events.add(recipeAddedEvent);

            for (int renameNumber = 1; renameNumber < numberOfEventsPerStream; renameNumber++) {
                final Event recipeRenamedEvent = cakeshopEventGenerator.createRecipeRenamedEvent(recipeId, seed, renameNumber, positionInStreamIterator);
                events.add(recipeRenamedEvent);
            }
        }

        batchEventInserter.updateEventStreamTable(streamIds);
        batchEventInserter.updateEventLogTable(events);
        batchEventInserter.updatePublishQueueTableWithEvents(events);
    }

    private void setPayloadsOfEventsInvalid() throws Exception {

        final List<UUID> eventIds = getEventIds();

        try (final Connection connection = eventStoreDataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement("UPDATE published_event set payload = ? where id = ?")) {

            for (final UUID eventId : eventIds) {
                final String dodgyPayload = createObjectBuilder()
                        .add("dodgyProperty", "Event with id '" + eventId + "' is dodgy")
                        .build().toString();

                preparedStatement.setString(1, dodgyPayload);
                preparedStatement.setObject(2, eventId);

                preparedStatement.addBatch();
            }

            preparedStatement.executeBatch();
        }
    }

    private List<UUID> getEventIds() throws Exception {

        final List<UUID> eventIds = new ArrayList<>();
        try (final Connection connection = eventStoreDataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement("SELECT id from published_event");
             final ResultSet resultSet = preparedStatement.executeQuery()) {

            while (resultSet.next()) {
                eventIds.add((UUID) resultSet.getObject(1));
            }
        }

        return eventIds;
    }

    private Optional<SystemCommandStatus> commandNoLongerInProgress(final SystemCommanderMBean systemCommanderMBean, final UUID commandId) {

        final SystemCommandStatus systemCommandStatus = systemCommanderMBean.getCommandStatus(commandId);
        System.out.printf("Polling for command state to be COMMAND_COMPLETE||COMMAND_FAILED for commandId: %s\n", commandId);

        final CommandState commandState = systemCommandStatus.getCommandState();
        if (commandState == COMMAND_COMPLETE || commandState == COMMAND_FAILED) {
            return of(systemCommandStatus);

        }

        return Optional.empty();
    }
}
