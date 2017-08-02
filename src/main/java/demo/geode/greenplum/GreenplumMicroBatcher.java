/*
 * Copyright 2017 Charlie Black
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package demo.geode.greenplum;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.geode.cache.Declarable;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.asyncqueue.AsyncEvent;
import org.apache.geode.cache.asyncqueue.AsyncEventListener;
import org.apache.geode.pdx.PdxInstance;

import javax.sql.DataSource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.codahale.metrics.MetricRegistry.name;
import static demo.geode.greenplum.MicroBatcherConstants.*;

/**
 * This class assumes:
 * <p><ul>
 * <li><PDX read serialized is true.
 * </ul>
 */

public class GreenplumMicroBatcher implements AsyncEventListener, Declarable {


    private static final Log LOG = LogFactory.getLog(GreenplumMicroBatcher.class);
    private final MetricRegistry metrics = new MetricRegistry();
    private final Timer writeDuration = metrics.timer(name(GreenplumMicroBatcher.class, "writeDuration"));
    private final Histogram batchSize = metrics.histogram(name(GreenplumMicroBatcher.class, "batchSize"));

    private FileChannel interprocessLock;
    private DataSource dataSource;
    private ExecutorService executorService = Executors.newCachedThreadPool();
    private String pipeFileLocation;
    private String[] pdxfields;
    private char separatorChar = '\t';
    private boolean appendOperation = false;
    private String sqlText;

    @Override
    public boolean processEvents(List<AsyncEvent> events) {
        FileLock fileLock = null;
        try {
            fileLock = interprocessLock.lock();
            return doProcessEvents(events);
        } catch (IOException e) {
            LOG.error("Could not acquire lock on " + interprocessLock.toString(), e);
        } finally {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException e) {
                    LOG.error("Could not release lock on " + interprocessLock.toString(), e);
                }
            }
        }
        return true;
    }

    /**
     * With greenplum we are always going to write the operation might be a meta data to the line we write.
     *
     * @param events
     */
    private boolean doProcessEvents(List<AsyncEvent> events) {
        // Everyone likes metrics - size of the data we are pushing
        batchSize.update(events.size());
        // trigger the database to start listening on the pipe.
        Future future = executorService.submit(new TriggerRead());
        // Metric the time it takes to push the data.
        Timer.Context context = writeDuration.time();
        BufferedWriter bufferedWriter = null;
        try {
            // We are sending the data over a pipe to the GPFDist process.    This is the fastest method to send data
            // to greenplum in a binary fashion.
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pipeFileLocation)));
            // Iterate over the contents of the batch
            for (AsyncEvent event : events) {
                StringBuffer stringBuffer = null;
                if (Operation.UPDATE.equals(event.getOperation()) || Operation.CREATE.equals(event.getOperation())) {
                    PdxInstance pdxInstance = (PdxInstance) event.getDeserializedValue();
                    stringBuffer = getText(pdxInstance);
                }
                //TODO what does it mean to delete.
                if (stringBuffer != null) {
                    if (appendOperation) {
                        stringBuffer.append(separatorChar).append(event.getOperation().ordinal);
                    }
                    // Newline
                    stringBuffer.append('\n');
                    // Write the entire contents to the pipe
                    bufferedWriter.write(stringBuffer.toString());
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Send the following to GP : " + stringBuffer.toString());
                    }
                }
            }
            bufferedWriter.flush();
            // lets make sure the SQL returns so we don't get the RDBMS in some whacky state.
            future.get();
        } catch (InterruptedException | ExecutionException | IOException e) {
            LOG.error("Couldn't write to the pipe ", e);
            throw new RuntimeException(e);
        } finally {
            // Stop the timer to send to greenplum
            context.stop();
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException e) {
                    LOG.error("Could not gracefully close the connection.", e);
                }
            }
        }

        return true;
    }

    private StringBuffer getText(PdxInstance pdxInstance) {
        StringBuffer buffer = new StringBuffer();
        boolean firstTime = true;
        for (String field : pdxfields) {
            if (firstTime) {
                firstTime = false;
            } else {
                buffer.append(separatorChar);
            }
            if (pdxInstance.hasField(field)) {
                Object value = pdxInstance.getField(field);
                if (value == null) {
                    buffer.append("null");
                } else {
                    buffer.append(value.toString());
                }
            }
        }
        return buffer;
    }

    @Override
    public void close() {

    }

    @Override
    public void init(Properties props) {
        setupConnectionPool(props);


        String tableName = props.getProperty(TABLE_NAME);
        LOG.info("Using table name - " + tableName);
        String extTableName = props.getProperty(EXT_TABLE_NAME);
        LOG.info("Using external table name - " + extTableName);
        assert StringUtils.isNotEmpty(tableName) && StringUtils.isNotEmpty(extTableName);
        String temp = props.getProperty(PDX_FIELDS);
        LOG.info("PDX fields to send - " + temp);
        assert StringUtils.isNotEmpty(temp);
        pdxfields = temp.split(":");
        sqlText = "INSERT  INTO " + tableName + " SELECT  * from " + extTableName + " ;";
        LOG.info("The SQL command to execute to open the pipe is - " + sqlText);
        pipeFileLocation = props.getProperty(PIPE_FILE_LOCATION);
        LOG.info("Pipe file location - " + pipeFileLocation);
        assert StringUtils.isNotEmpty(pipeFileLocation);
        appendOperation = Boolean.valueOf(props.getProperty(APPEND_OPERATION, Boolean.FALSE.toString()));
        LOG.info("The microbatcher will be appending the operation ordinal " + appendOperation);
        temp = props.getProperty(PIPE_FILE_LOCK);
        LOG.info("Using the file to coordinate the lock on the pipe so we don't get mixed streams - " + temp);
        assert StringUtils.isNotEmpty(temp);
        try {
            File file = new File(temp);
            file.mkdirs();
            interprocessLock = new RandomAccessFile(file, "rw").getChannel();
        } catch (FileNotFoundException e) {
            LOG.error("Tried to create a file for locking. ");
            throw new RuntimeException(e);
        }
        separatorChar = props.getProperty(SEPARATOR_CHAR, Integer.toString((int)'\t')).trim().charAt(0);
        LOG.info("Using the following char as a separator char - " + ((int) separatorChar));
    }

    private void setupConnectionPool(Properties properties) {
        Properties props = new Properties();
        //TODO should make this external so the app isn't dependant on a given connection pool.
        String connectionPoolProperties = properties.getProperty(CONNECTION_POOL_PROPERTIES);
        assert StringUtils.isNotEmpty(connectionPoolProperties);
        try (Reader reader = new BufferedReader(new FileReader(connectionPoolProperties))) {
            props.load(reader);
        } catch (IOException e) {
            LOG.error("Could not load connection pool properties file.", e);
        }
        //TODO - I shouldn't output the properties file since it will contain the DB user and password.
        // but for now I am ok with it.
        LOG.info("Connection Pool Properties:");
        properties.entrySet().forEach(entry -> {
            LOG.info("name: " + entry.getKey() + ", value: " + entry.getValue());
        });
        HikariConfig config = new HikariConfig(props);
        dataSource = new HikariDataSource(config);
    }

    private class TriggerRead implements Runnable {

        @Override
        public void run() {
            Connection connection = null;
            Statement sql;
            DatabaseMetaData dbmd;
            try {
                connection = dataSource.getConnection();
                sql = connection.createStatement();
                sql.executeUpdate(sqlText);
            } catch (SQLException e) {
                LOG.error("Could not trigger database to read from external table.", e);
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        LOG.error("Could not close data source connection.", e);
                    }
                }
            }
        }
    }
}
