package org.unizin.cmp.oai.harvester.service;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.unizin.cmp.oai.harvester.HarvestParams;

/**
 * H2 stored functions.
 * <p>
 * These must be aliased via <tt>CREATE ALIAS</tt>. See <a href=
 * "http://www.h2database.com/html/features.html#user_defined_functions">H2 user
 * defined functions</a> for details.
 * </p>
 */
public final class H2Functions {
    public static final class JobInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        long id;
        List<Long> harvestIDs = new ArrayList<>();
    }

    public static JobInfo createJob(final Connection c,
            final List<HarvestParams> params) throws SQLException {
        // Do not close the handle! causes exceptions.
        final Handle h = DBI.open(c);
        final JobJDBI jdbi = h.attach(JobJDBI.class);
        final long jobID = jdbi.createJob();
        final JobInfo info = new JobInfo();
        info.id = jobID;
        params.forEach(x -> info.harvestIDs.add(createHarvest(c, jobID, x)));
        return info;
    }

    public static long createHarvest(final Connection c,
            final long jobID, final HarvestParams params) {
        // Do not close the handle! causes exceptions.
        final Handle h = DBI.open(c);
        final JobJDBI jdbi = h.attach(JobJDBI.class);
        final long repositoryID = jdbi.findRepositoryIDByBaseURI(
                params.getBaseURI().toString());
        return jdbi.createHarvest(jobID, repositoryID,
                params.toString(), params.getVerb());
    }

    /** No instances allowed. */
    private H2Functions() { }
}
