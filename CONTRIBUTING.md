# Contributing to Reactive RTC

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## Getting Started

### Prerequisites
- Java 21+
- Maven 3.9+
- Docker & Docker Compose
- Git

### Setup Development Environment

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-org/reactive-rtc.git
   cd reactive-rtc
   ```

2. **Build the project**
   ```bash
   mvn clean package
   ```

3. **Run tests**
   ```bash
   mvn test
   ```

4. **Run locally**
   ```bash
   make run-infra  # Start Kafka + Redis
   make run-lb     # Start load-balancer
   make run-socket # Start socket node
   ```

---

## Code Guidelines

### Java Style
- **Java 21** features encouraged
- **Reactor patterns** - Use reactive streams, avoid blocking
- **Lombok** - Use `@Value`, `@Builder` for DTOs
- **JavaDoc** - Document all public APIs
- **No wildcards** - Use explicit imports

### SOLID Principles
This project follows SOLID principles:
- **S**: Each class has one responsibility
- **O**: Extend via interfaces, not modification
- **L**: All implementations substitutable
- **I**: Focused interfaces (ISessionManager, IRedisService, etc.)
- **D**: Depend on abstractions (interfaces), not concretions

### Code Structure
```java
// Good: Dependency injection with interface
public class MyService {
    private final IRedisService redisService;
    
    public MyService(IRedisService redisService) {
        this.redisService = redisService;
    }
}

// Bad: Direct dependency on concrete class
public class MyService {
    private final RedisService redisService = new RedisService();
}
```

---

## Testing

### Writing Tests
- **Test naming**: `test<Scenario>_<ExpectedBehavior>`
- **Assertions**: Use descriptive messages
- **High volume**: Test with realistic user counts (10K+)
- **No mocks**: Use test stubs/fakes instead of Mockito

### Example Test
```java
@Test
void testHighLoad_TriggersScaleOut() {
    List<Heartbeat> heartbeats = createHeartbeats(3, 4500, 900.0, 300.0);
    
    ScalingDirective directive = engine.computeScalingDirective(heartbeats).block();
    
    assertNotNull(directive);
    assertEquals(ScalingDirective.Action.SCALE_OUT, directive.getAction(),
            "High load should trigger scale-out");
}
```

### Running Tests
```bash
# All tests
mvn test

# Specific module
mvn test -pl core

# Specific test class
mvn test -Dtest=ConsistentHashRingTest

# Verification
mvn verify
```

---

## Pull Request Process

### 1. Create Feature Branch
```bash
git checkout -b feature/my-awesome-feature
```

### 2. Make Changes
- Write code following guidelines
- Add tests for new functionality
- Update documentation if needed

### 3. Verify Locally
```bash
mvn clean verify     # Build + all tests
./verify.sh          # Automated verification
```

### 4. Commit
```bash
git add .
git commit -m "feat: add awesome feature

- Implement XYZ
- Add tests for XYZ
- Update documentation"
```

Use conventional commits:
- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation only
- `test:` - Adding tests
- `refactor:` - Code refactoring
- `perf:` - Performance improvement

### 5. Push and Create PR
```bash
git push origin feature/my-awesome-feature
```

Then create a Pull Request with:
- Clear description of changes
- Link to related issues
- Test results
- Screenshots (if UI changes)

---

## Areas for Contribution

### High Priority
- [ ] Prometheus metrics integration (replace SimpleMeterRegistry)
- [ ] JWT authentication for WebSocket connections
- [ ] Message encryption (E2E)
- [ ] Admin dashboard (web UI)

### Medium Priority
- [ ] Multi-tenancy support
- [ ] Rate limiting per user
- [ ] Geo-aware routing
- [ ] Message persistence (beyond Redis buffers)

### Low Priority
- [ ] GraphQL API for load-balancer
- [ ] Client SDKs (JavaScript, Python, Go)
- [ ] Grafana dashboards
- [ ] Helm charts

---

## Module Structure

### Core Module
- **Purpose**: Shared models and utilities
- **No external runtime** dependencies (except Jackson, Guava)
- **All classes immutable** where possible

### Socket Module
- **Purpose**: WebSocket server
- **Key classes**: SessionManager, WebSocketHandler, KafkaService
- **Interfaces**: ISessionManager, IRedisService, IKafkaService

### Load-Balancer Module
- **Purpose**: Control plane
- **Key classes**: RingManager, ScalingEngine, HttpServer
- **Interfaces**: IRingManager, IRingPublisher

---

## Documentation

### When to Update Docs
- **New features** → Update README.md + ARCHITECTURE.md
- **API changes** → Update README.md API section
- **Deployment changes** → Update deploy/DEPLOYMENT_GUIDE.md
- **Configuration changes** → Update README.md Configuration section

### Documentation Files
- `README.md` - Main documentation
- `QUICKSTART.md` - 5-minute setup guide
- `ARCHITECTURE.md` - System design
- `CONTRIBUTING.md` - This file
- `deploy/DEPLOYMENT_GUIDE.md` - Operations manual

---

## Build & Release

### Version Numbering
We use Semantic Versioning (MAJOR.MINOR.PATCH):
- **MAJOR**: Breaking changes
- **MINOR**: New features (backward compatible)
- **PATCH**: Bug fixes

### Release Process
1. Update version in all `pom.xml` files
2. Update `CHANGELOG.md`
3. Tag release: `git tag v1.0.0`
4. Build release artifacts: `mvn clean package`
5. Push tag: `git push origin v1.0.0`
6. Create GitHub release with artifacts

---

## Code Review Checklist

Before submitting PR, ensure:
- [ ] Code follows SOLID principles
- [ ] All tests pass (`mvn verify`)
- [ ] New tests added for new features
- [ ] Documentation updated
- [ ] No wildcard imports
- [ ] JavaDoc on public APIs
- [ ] No blocking I/O on hot paths
- [ ] Proper error handling
- [ ] Metrics added for new operations
- [ ] Logging with appropriate levels

---

## Getting Help

- **Questions**: Open a GitHub Discussion
- **Bugs**: Open a GitHub Issue
- **Security**: Email security@reactive-rtc.example.com
- **Chat**: Join our Discord/Slack (TBD)

---

## Code of Conduct

Be respectful, constructive, and collaborative. We're all here to build great software together.

---

## Thank You!

Every contribution, no matter how small, makes a difference. Thank you for helping make reactive-rtc better! 🙏

**Happy coding! 🚀**











