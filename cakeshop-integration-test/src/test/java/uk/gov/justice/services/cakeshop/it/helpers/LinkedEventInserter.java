package uk.gov.justice.services.cakeshop.it.helpers;

import static uk.gov.justice.services.common.converter.ZonedDateTimes.toSqlTimestamp;

import uk.gov.justice.services.eventsourcing.repository.jdbc.event.LinkedEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

public class LinkedEventInserter {

    private final DataSource eventStoreDataSource;

    public LinkedEventInserter(final DataSource eventStoreDataSource) {
        this.eventStoreDataSource = eventStoreDataSource;
    }

    public void insert(final LinkedEvent linkedEvent) {

        final String sql = "INSERT INTO event_log (" +
                "id," +
                "stream_id," +
                "position_in_stream," +
                "name," +
                "payload," +
                "metadata," +
                "date_created," +
                "event_number," +
                "previous_event_number) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";

        try(final Connection connection = eventStoreDataSource.getConnection();
            final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.setObject(1, linkedEvent.getId());
            preparedStatement.setObject(2, linkedEvent.getStreamId());
            preparedStatement.setLong(3, linkedEvent.getPositionInStream());
            preparedStatement.setString(4, linkedEvent.getName());
            preparedStatement.setString(5, linkedEvent.getPayload());
            preparedStatement.setString(6, linkedEvent.getMetadata());
            preparedStatement.setTimestamp(7, toSqlTimestamp(linkedEvent.getCreatedAt()));
            preparedStatement.setLong(8, linkedEvent.getEventNumber().orElse(null));
            preparedStatement.setLong(9, linkedEvent.getPreviousEventNumber());

            preparedStatement.executeUpdate();
        } catch (final SQLException e) {
            throw new RuntimeException("Failed to run query '" + sql + "' against the event store", e);
        }
    }
}

