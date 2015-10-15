package org.unizin.cmp.retrieval;

import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.work.api.WorkManager;

@Operation(id = RetrieveCopyFromSourceRepository.ID,
           label = "Request copy from source repository",
           description = "Asynchronous wrapper for CopyFromSourceRepository")
public class RetrieveCopyFromSourceRepository {
    public static final String ID = "UnizinCMP.RetrieveCopyFromSourceRepository";

    @Context
    WorkManager workManager;

    @Context
    RepositoryManager repositoryManager;

    @Context
    CoreSession session;

    @OperationMethod
    public DocumentModel run(DocumentModel doc) {
        if (!"pending".equals(doc.getPropertyValue(CopyFromSourceRepository.STATUS_PROP))) {
            doc.setPropertyValue(CopyFromSourceRepository.STATUS_PROP, "pending");
            session.save();
            workManager.schedule(new RetrieveCopyWork(
                    repositoryManager.getDefaultRepositoryName(),
                    doc.getId()));
        }
        return doc;
    }

}
