/*
 * Copyright (c) 2013 KloudTek Ltd
 */

package com.kloudtek.systyrant.resource;

import com.kloudtek.systyrant.*;
import com.kloudtek.systyrant.exception.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.kloudtek.util.StringUtils.isNotEmpty;

public class ResourceManagerImpl implements ResourceManager {
    private STContext context;
    private static final Logger logger = LoggerFactory.getLogger(ResourceManagerImpl.class);
    private List<ResourceFactory> resourceFactories = new ArrayList<>();
    private List<Resource> resources = new ArrayList<>();
    private HashMap<Resource, List<Resource>> parentChildIndex;
    /** Concurrency lock */
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;
    /** Flag indicating if element creation is allowed */
    private boolean createAllowed = true;
    private final Map<String, ResourceFactory> fqnResourceIndex = new HashMap<>();
    private HashSet<FQName> uniqueResourcesCreated = new HashSet<>();

    public ResourceManagerImpl(STContext context) {
        this.context = context;
    }

    @Override
    public Iterator<Resource> iterator() {
        rlock();
        try {
            return resources.iterator();
        } finally {
            rulock();
        }
    }

    @Override
    public void setContext(STContext context) {
        this.context = context;
    }

    @Override
    public List<ResourceFactory> getResourceFactories() {
        rlock();
        try {
            return Collections.unmodifiableList(resourceFactories);
        } finally {
            rulock();
        }
    }

    @Override
    public List<Resource> getResources() {
        rlock();
        try {
            return Collections.unmodifiableList(resources);
        } finally {
            rulock();
        }
    }

    @Override
    public List<Resource> getChildrens(Resource resource) {
        rlock();
        try {
            return Collections.unmodifiableList(getChildrensInternalList(resource));
        } finally {
            rulock();
        }
    }

    private List<Resource> getChildrenOnDemandSearch(Resource resource) {
        ArrayList<Resource> list = new ArrayList<>();
        wlock();
        for (Resource rs : resources) {
            Resource p = rs.getParent();
            if (p != null && p.equals(resource)) {
                list.add(rs);
            }
        }
        wulock();
        return list;
    }

    @Override
    public boolean isCreateAllowed() {
        rlock();
        try {
            return createAllowed;
        } finally {
            rulock();
        }
    }

    @Override
    public void setCreateAllowed(boolean createAllowed) {
        wlock();
        try {
            this.createAllowed = createAllowed;
        } catch (Exception e) {
            wulock();
        }
    }

    // Resource creation

    /**
     * Used to create an Resource instance.
     * Important notes: Must only be called before the pre-execution
     *
     * @param fqname Fully qualified resource name
     * @param parent If specified, the new resource will set this as it's parent
     */
    @Override
    public Resource createResource(@NotNull FQName fqname, @Nullable Collection<ResourceMatcher> importPaths, @Nullable Resource parent) throws ResourceCreationException {
        wlock();
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Creating resource {}", fqname);
            }
            if (!createAllowed) {
                throw new ResourceCreationException("Resources created not allowed at this time.");
            }
            ResourceFactory factory;
            factory = findFactory(fqname, importPaths);
            if (factory.isUnique()) {
                if (uniqueResourcesCreated.contains(factory.getFQName())) {
                    throw new ResourceCreationException("Cannot create more than one instance of " + fqname.toString());
                }
                uniqueResourcesCreated.add(fqname);
            }
            Resource resource = factory.create(context);
            Resource defaultParent = context.getDefaultParent();
            if (parent != null) {
                resource.setParent(parent);
            } else if (defaultParent != null) {
                resource.setParent(defaultParent);
            }
            resources.add(resource);
            if (logger.isDebugEnabled()) {
                logger.debug("Created resource {}", fqname);
            }
            return resource;
        } finally {
            wulock();
        }
    }

    @Override
    public Resource createResource(@NotNull String fqname, @Nullable Collection<ResourceMatcher> importPaths, @Nullable Resource parent) throws ResourceCreationException {
        return createResource(new FQName(fqname), importPaths, parent);
    }

    @Override
    public Resource createResource(@NotNull String fqname, @Nullable Collection<ResourceMatcher> importPaths) throws ResourceCreationException {
        return createResource(new FQName(fqname), importPaths, null);
    }

    @Override
    public Resource createResource(@NotNull FQName fqname) throws ResourceCreationException {
        return createResource(fqname, null, null);
    }

    @Override
    public Resource createResource(@NotNull String fqname) throws ResourceCreationException {
        return createResource(new FQName(fqname), null, null);
    }

    // -------------------------
    // Lookups
    // -------------------------

    @Override
    @NotNull
    public List<Resource> findResources(@Nullable String pkg, @Nullable String name, @Nullable String id) {
        rlock();
        try {
            ArrayList<Resource> results = new ArrayList<>();
            for (Resource resource : resources) {
                if ((pkg != null && resource.getPkg().equalsIgnoreCase(pkg) ||
                        (name != null && resource.getName().equalsIgnoreCase(name)) ||
                        (isNotEmpty(id) && resource.getId().equals(id)))) {
                    results.add(resource);
                }
            }
            return results;
        } finally {
            rulock();
        }
    }

    @NotNull
    private ResourceFactory findFactory(FQName name, Collection<ResourceMatcher> importPaths) throws MultipleResourceMatchException, ResourceNotFoundException, ResourceCreationException {
        rlock();
        try {
            ResourceFinder rfinder = new ResourceFinder(name, importPaths);
            if (!rfinder.found()) {
                // dynamically loading matching DSL file
                if (name.getPkg() != null) {
                    dynaLoad(name.getPkg(), name.getName());
                } else {
                    for (ResourceMatcher importPath : importPaths) {
                        dynaLoad(importPath.getPkg(), name.getName());
                    }
                }
                // Retrying to find factory
                rfinder = new ResourceFinder(name, importPaths);
            }
            return rfinder.getMatch();
        } finally {
            rulock();
        }
    }

    private void dynaLoad(@NotNull String pkg, @NotNull String name) throws ResourceCreationException {
        URL url = null;
        for (Library library : context.getLibraries()) {
            url = library.getElementScript(pkg, name);
            if (url != null) {
                break;
            }
        }
        if (url != null) {
            try {
                context.runScript(pkg, url.toURI());
            } catch (URISyntaxException | ScriptException | IOException e) {
                throw new ResourceCreationException(e.getMessage(), e);
            }
        }
    }

    @Override
    public List<Resource> findResourcesById(@NotNull String id) throws STRuntimeException {
        rlock();
        try {
            ArrayList<Resource> list = new ArrayList<>();
            for (Resource resource : resources) {
                if (id.equals(resource.getId())) {
                    list.add(resource);
                }
            }
            return list;
        } finally {
            rulock();
        }
    }


    // Resource registration

    @Override
    public void registerResources(Collection<ResourceFactory> factories) throws InvalidResourceDefinitionException {
        wlock();
        try {
            for (ResourceFactory factory : factories) {
                registerResources(factory);
            }
        } catch (InvalidResourceDefinitionException e) {
            rulock();
        }
    }

    /**
     * Register a new resource factory
     *
     * @param factory Resource Factory
     * @throws com.kloudtek.systyrant.exception.InvalidResourceDefinitionException
     *          If the factory is invalid.
     */
    @Override
    public void registerResources(ResourceFactory factory) throws InvalidResourceDefinitionException {
        wlock();
        try {
            factory.validate();
            FQName fqName = factory.getFQName();
            fqnResourceIndex.put(fqName.toString(), factory);
            resourceFactories.add(factory);
        } finally {
            wulock();
        }
    }

    @Override
    public void registerJavaResource(Class<?> clazz) throws InvalidResourceDefinitionException {
        FQName fqName = STHelper.getFQName(clazz);
        if (fqName == null) {
            throw new InvalidResourceDefinitionException("No FQName specified for java resource: " + clazz.getName());
        } else {
            registerJavaResource(clazz, fqName);
        }
    }

    @Override
    public void registerJavaResource(Class<?> clazz, @NotNull String fqname) throws InvalidResourceDefinitionException {
        registerJavaResource(clazz, new FQName(fqname));
    }

    @Override
    public void registerJavaResource(Class<?> clazz, @NotNull FQName fqname) throws InvalidResourceDefinitionException {
        registerResources(new JavaResourceFactory(clazz, fqname, null));
    }

    @Override
    public void close() {
        wlock();
        try {
            for (ResourceFactory factory : resourceFactories) {
                try {
                    factory.close();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        } finally {
            wulock();
            closed = true;
        }
    }

    /** Calling this method will prepareForExecution all indexes, resolve all references, and re-order the resources based on dependencies */
    @Override
    public void prepareForExecution() throws InvalidDependencyException {
        wlock();
        try {
            // add dependency on parent if missing
            for (Resource resource : resources) {
                Resource parent = resource.getParent();
                if (parent != null && !resource.getResolvedDeps().contains(parent)) {
                    resource.addDependency(parent);
                }
            }
            // mandatory children resolution
            resolve(true);
            // build parent/child map
            parentChildIndex = new HashMap<>();
            for (Resource resource : resources) {
                if (resource.getParent() != null) {
                    getChildrensInternalList(resource.getParent()).add(resource);
                }
            }
            // make dependent on resource if dependent on parent (childrens excluded from this rule)
            for (Resource resource : resources) {
                for (Resource dep : resource.getResolvedDeps()) {
                    if (resource.getParent() == null || !resource.getParent().equals(dep)) {
                        makeDependentOnChildren(resource, dep);
                    }
                }
            }
            // Sort according to dependencies
            ResourceSorter.sort(resources);
        } finally {
            wulock();
        }
    }

    @Override
    public void resolve(boolean strict) throws InvalidDependencyException {
        wlock();
        try {
            for (Resource resource : resources) {
                resource.resolveDepencies(strict);
            }
        } finally {
            wulock();
        }
    }

    @Override
    public boolean hasResources() {
        rlock();
        try {
            return !resources.isEmpty();
        } finally {
            rulock();
        }
    }

    private List<Resource> getChildrensInternalList(Resource resource) {
        List<Resource> childrens = parentChildIndex.get(resource);
        if (childrens == null) {
            childrens = new ArrayList<>();
            parentChildIndex.put(resource, childrens);
        }
        return childrens;
    }

    private void makeDependentOnChildren(Resource resource, Resource dependency) {
        LinkedList<Resource> list = new LinkedList<>();
        list.addAll(getChildrensInternalList(dependency));
        while (!list.isEmpty()) {
            Resource el = list.removeFirst();
            resource.addDependency(el);
            list.addAll(getChildrensInternalList(el));
        }
    }

    private void rulock() {
        lock.readLock().unlock();
        if (closed) {
            throw new RuntimeException("Attempted to access resource manager that has already been closed.");
        }
    }

    private void rlock() {
        lock.readLock().lock();
    }

    private void wlock() {
        lock.writeLock().lock();
        if (closed) {
            throw new RuntimeException("Attempted to access resource manager that has already been closed.");
        }
    }

    private void wulock() {
        lock.writeLock().unlock();
    }

    public class ResourceFinder {
        private final FQName name;
        private ResourceFactory fac;

        public ResourceFinder(FQName name, Collection<ResourceMatcher> importPaths) throws MultipleResourceMatchException {
            this.name = name;
            if (name.getPkg() != null) {
                set(fqnResourceIndex.get(name.toString()));
            } else {
                for (ResourceFactory resourceFactory : resourceFactories) {
                    if (ResourceMatcher.matchAll(importPaths, resourceFactory.getFQName()) && resourceFactory.getName().equals(name.getName())) {
                        set(resourceFactory);
                    }
                }
            }
        }

        public void set(ResourceFactory newMatch) throws MultipleResourceMatchException {
            if (fac != null) {
                throw new MultipleResourceMatchException("Found more than one match for " + name.getName() + ": " + fac.getFQName() + " and " + newMatch.getFQName().toString());
            } else {
                fac = newMatch;
            }
        }

        public boolean found() {
            return fac != null;
        }

        public ResourceFactory getMatch() throws ResourceNotFoundException {
            if (fac == null) {
                throw new ResourceNotFoundException("Unable to find resource " + name);
            } else {
                return fac;
            }
        }
    }
}
