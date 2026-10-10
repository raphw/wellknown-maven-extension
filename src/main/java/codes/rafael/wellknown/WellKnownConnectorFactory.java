package codes.rafael.wellknown;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.connector.RepositoryConnector;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.transfer.NoRepositoryConnectorException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Named("wellknown")
@Singleton
public class WellKnownConnectorFactory implements RepositoryConnectorFactory {

    private final Set<RepositoryConnectorFactory> factories;

    @Inject
    public WellKnownConnectorFactory(Set<RepositoryConnectorFactory> factories) {
        this.factories = factories;
    }

    @Override
    public RepositoryConnector newInstance(RepositorySystemSession session, RemoteRepository repository)
            throws NoRepositoryConnectorException {
        Configuration configuration = Configuration.ofProperties(session.getConfigProperties());
        if (!configuration.discovery() || session.isOffline()) {
            throw new NoRepositoryConnectorException(repository, session.isOffline()
                    ? "No domain is asked for its discovery file offline"
                    : "Discovery is switched off by -Dwellknown.discovery=false");
        }
        Discovery discovery = (Discovery) session.getData().computeIfAbsent(Discovery.class,
                () -> new Discovery(configuration.location(),
                        WellKnownClient.ofSession(session, configuration),
                        configuration.timeout() == 0 ? null : Duration.ofMillis(configuration.timeout()),
                        session.getLocalRepository() == null
                                ? null
                                : session.getLocalRepository().getBasedir().toPath().resolve(".wellknown"),
                        RepositoryPolicy.UPDATE_POLICY_ALWAYS.equals(session.getUpdatePolicy())
                                ? Duration.ZERO
                                : Duration.ofSeconds(configuration.ttl())));
        return new WellKnownConnector(session, repository, delegate(session, repository), discovery, this::delegate);
    }

    RepositoryConnector delegate(RepositorySystemSession session, RemoteRepository repository)
            throws NoRepositoryConnectorException {
        List<RepositoryConnectorFactory> candidates = factories.stream()
                .filter(factory -> !(factory instanceof WellKnownConnectorFactory))
                .sorted(Comparator.comparing(RepositoryConnectorFactory::getPriority).reversed())
                .toList();
        NoRepositoryConnectorException last = null;
        for (RepositoryConnectorFactory factory : candidates) {
            try {
                return factory.newInstance(session, repository);
            } catch (NoRepositoryConnectorException e) {
                last = e;
            }
        }
        throw new NoRepositoryConnectorException(repository, "No connector beside discovery accepts " + repository
                + " of " + candidates.size() + " candidate factories", last);
    }

    @Override
    public float getPriority() {
        return 1000;
    }
}
