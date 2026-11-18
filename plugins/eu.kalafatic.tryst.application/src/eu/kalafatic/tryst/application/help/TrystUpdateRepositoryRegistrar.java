package eu.kalafatic.tryst.application.help;

import eu.kalafatic.tryst.core.log.TrystLog;
import java.io.File;
import java.net.URI;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Registers the product's local update repository ({@code <install>/updates}) in the
 * provisioning profile when that directory exists, so the standard Eclipse "Check for
 * Updates" discovers published updates. Registration is additive and idempotent: it
 * never removes or overrides user-configured repositories.
 */
public final class TrystUpdateRepositoryRegistrar {

    private static final String REPOSITORY_NAME = "TRYST Local Updates";

    private final BundleContext context;
    private ServiceTracker<IProvisioningAgent, IProvisioningAgent> tracker;
    private Thread registrationThread;

    public TrystUpdateRepositoryRegistrar(BundleContext context) {
        this.context = context;
    }

    public void start() {
        if (tracker != null) {
            return;
        }
        tracker = new ServiceTracker<>(context, IProvisioningAgent.class, null) {
            @Override
            public IProvisioningAgent addingService(ServiceReference<IProvisioningAgent> reference) {
                IProvisioningAgent agent = super.addingService(reference);
                registerWhenReady(agent);
                return agent;
            }
        };
        tracker.open();
        IProvisioningAgent agent = tracker.getService();
        if (agent != null) {
            registerWhenReady(agent);
        }
    }

    public void stop() {
        if (tracker != null) {
            tracker.close();
            tracker = null;
        }
        if (registrationThread != null && registrationThread.isAlive()) {
            registrationThread.interrupt();
            registrationThread = null;
        }
    }

    /**
     * The provisioning agent is registered before its repository managers may be
     * available; retry briefly in a background thread until they are.
     */
    private void registerWhenReady(final IProvisioningAgent agent) {
        if (registrationThread != null && registrationThread.isAlive()) {
            return;
        }
        registrationThread = new Thread(() -> {
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    if (register(agent)) {
                        return;
                    }
                } catch (Throwable t) {
                    TrystLog.debug(TrystUpdateRepositoryRegistrar.class,
                            "Update repository registration attempt failed: " + t.getMessage());
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "tryst-update-repository-registrar");
        registrationThread.setDaemon(true);
        registrationThread.start();
    }

    private static boolean register(IProvisioningAgent agent) {
        IMetadataRepositoryManager metaManager =
                agent.getService(IMetadataRepositoryManager.class);
        IArtifactRepositoryManager artifactManager =
                agent.getService(IArtifactRepositoryManager.class);
        if (metaManager == null || artifactManager == null) {
            return false;
        }
        File updatesDir = new File(
                Platform.getInstallLocation().getURL().getPath(), "updates");
        if (!updatesDir.isDirectory()) {
            return true;
        }
        URI repoUri = updatesDir.toURI();
        if (!metaManager.contains(repoUri)) {
            metaManager.addRepository(repoUri);
            metaManager.setRepositoryProperty(repoUri, IRepository.PROP_NICKNAME, REPOSITORY_NAME);
        }
        if (!artifactManager.contains(repoUri)) {
            artifactManager.addRepository(repoUri);
            artifactManager.setRepositoryProperty(repoUri, IRepository.PROP_NICKNAME, REPOSITORY_NAME);
        }
        return true;
    }
}
