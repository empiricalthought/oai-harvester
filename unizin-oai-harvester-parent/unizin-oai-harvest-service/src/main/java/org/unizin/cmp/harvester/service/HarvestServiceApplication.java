package org.unizin.cmp.harvester.service;

import java.util.concurrent.ExecutorService;

import javax.sql.DataSource;

import org.apache.http.client.HttpClient;
import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unizin.cmp.harvester.job.HarvestedOAIRecord;
import org.unizin.cmp.harvester.service.config.DynamoDBConfiguration;
import org.unizin.cmp.harvester.service.config.HarvestHttpClientBuilder;
import org.unizin.cmp.harvester.service.config.HarvestServiceConfiguration;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.codahale.metrics.MetricRegistry;

import io.dropwizard.Application;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.jdbi.DBIHealthCheck;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public final class HarvestServiceApplication
extends Application<HarvestServiceConfiguration> {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            HarvestServiceApplication.class);
    private static final String CONNECTION_POOL_NAME =
            "HarvestService Database Connection Pool";
    private static final String HTTP_CLIENT_NAME =
            "HarvestService HTTP Client";

    private Bootstrap<HarvestServiceConfiguration> bootstrap;
    private AmazonDynamoDB dynamoDBClient;
    private DynamoDBMapper dynamoDBMapper;

    @Override
    public void initialize(final Bootstrap<HarvestServiceConfiguration> bootstrap) {
        super.initialize(bootstrap);
        this.bootstrap = bootstrap;
    }

    private DataSource createConnectionPool(
            final HarvestServiceConfiguration configuration,
            final Environment environment) {
        final DataSourceFactory dsf = configuration.getDataSourceFactory();
        final MetricRegistry mr = bootstrap.getMetricRegistry();
        final ManagedDataSource ds = dsf.build(mr, CONNECTION_POOL_NAME);
        environment.lifecycle().manage(ds);
        environment.healthChecks().register(CONNECTION_POOL_NAME,
                new DBIHealthCheck(new DBI(ds), dsf.getValidationQuery()));
        return ds;
    }

    private void createMapper(
            final DynamoDBConfiguration configuration) {
        dynamoDBClient = configuration.build();
        dynamoDBMapper = configuration.getRecordMapperConfiguration().build(
                dynamoDBClient);
    }

    private void createDynamoDBTable() {
        // Throughput: ignored by local, but still required.
        final ProvisionedThroughput throughput = new ProvisionedThroughput(1L,
                1L);
        final CreateTableRequest req = dynamoDBMapper
                .generateCreateTableRequest(HarvestedOAIRecord.class)
                .withProvisionedThroughput(throughput);
        try {
            dynamoDBClient.createTable(req);
        } catch (final ResourceInUseException e) {
            LOGGER.warn("Exception creating DynamoDB table. It probably "
                    + "already exists.", e);
        }
    }

    @Override
    public void run(final HarvestServiceConfiguration conf,
            final Environment env) throws Exception {
        final DataSource ds = createConnectionPool(conf, env);
        final HttpClient httpClient = new HarvestHttpClientBuilder(env)
                .using(conf.getHttpClientConfiguration())
                .build(HTTP_CLIENT_NAME);
        final ExecutorService executor = conf.getJobConfiguration()
                .buildExecutorService(env);
        createMapper(conf.getDynamoDBConfiguration());
        createDynamoDBTable();
        final JobResource jr = new JobResource(ds, conf.getJobConfiguration(),
                httpClient, dynamoDBMapper, executor);
        env.jersey().register(jr);
    }

    public static void main(final String[] args) throws Exception {
        new HarvestServiceApplication().run(args);
    }
}