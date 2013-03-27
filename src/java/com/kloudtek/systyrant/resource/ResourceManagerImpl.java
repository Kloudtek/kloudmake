/*
 * Copyright (c) 2013 KloudTek Ltd
 */

package com.kloudtek.systyrant.resource;

import com.kloudtek.systyrant.FQName;
import com.kloudtek.systyrant.Library;
import com.kloudtek.systyrant.MultipleResourceMatchException;
import com.kloudtek.systyrant.STContext;
import com.kloudtek.systyrant.annotation.STResource;
import com.kloudtek.systyrant.exception.*;
import com.kloudtek.systyrant.host.Host;
import com.kloudtek.systyrant.resource.java.JavaResourceDefinitionFactory;
import com.kloudtek.systyrant.resource.query.ResourceQuery;
import com.kloudtek.systyrant.util.SetHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.ScriptException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.kloudtek.util.StringUtils.isNotEmpty;

public class ResourceManagerImpl implements ResourceManager {
    private STContext context;
    private final ThreadLocal<Resource> resourceScope;
    private static final Logger logger = LoggerFactory.getLogger(ResourceManagerImpl.class);
    private List<ResourceDefinition> resourceDefinitions = new ArrayList<>();
    private List<Resource> resources = new ArrayList<>();
    private HashMap<String, Resource> resourcesUidIndex = new HashMap<>();
    private HashMap<Resource, List<Resource>> parentChildIndex;
    /**
     * Concurrency lock
     */
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;
    /**
     * Flag indicating if element creation is allowed
     */
    private boolean createAllowed = true;
    private final Map<FQName, ResourceDefinition> resourceDefinitionsFQNIndex = new HashMap<>();
    private HashSet<FQName> uniqueResourcesCreated = new HashSet<>();
    private HashSet<ManyToManyResourceDependency> m2mDependencies = new HashSet<>();
    private HashSet<OneToManyResourceDependency> o2mDependencies = new HashSet<>();

    public ResourceManagerImpl(STContext context, ThreadLocal<Resource> resourceScope) {
        this.context = context;
        this.resourceScope = resourceScope;
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
    public List<ResourceDefinition> getResourceDefinitions() {
        rlock();
        try {
            return Collections.unmodifiableList(resourceDefinitions);
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

    // -------------------------
    // Resource Creation
    // -------------------------

    /**
     * Used to create an Resource instance.
     * Important notes: Must only be called before the pre-execution
     *
     * @param fqname Fully qualified resource name
     * @param id     Id of the resource or null if the id should be automatically generated.
     * @param parent If specified, the new resource will set this as it's parent
     */
    @Override
    public Resource createResource(@NotNull FQName fqname, @Nullable String id, @Nullable Resource parent,
                                   @Nullable Collection<ResourceMatcher> importPaths) throws ResourceCreationException {
        wlock();
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Creating resource {}", fqname);
            }
            if (!createAllowed) {
                throw new ResourceCreationException("Resources created not allowed at this time.");
            }
            ResourceDefinition definition;
            definition = findResourceDefinition(fqname, importPaths);
            String uid = null;
            Lock lock = parent != null ? parent.wlock() : context.getRootResourceLock().writeLock();
            lock.lock();
            try {
                if (id == null) {
                    id = definition.getFQName().toString();
                    String str = parent != null ? parent.getUid() + "." + id : id;
                    int count = 1;
                    while (context.findResourceByUid(str + count) != null) {
                        count++;
                    }
                    id = id + count;
                }
                uid = parent != null ? parent.getUid() + "." + id : id;
            } finally {
                lock.unlock();
            }
            if (resourcesUidIndex.containsKey(uid)) {
                throw new ResourceCreationException("There is already a resource with uid " + uid);
            }
            Resource resource = definition.create(context, id, uid, parent != null ? parent : context.getDefaultParent());
            resourcesUidIndex.put(uid, resource);
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
    public Resource createResource(@NotNull String fqname, String id, @Nullable Resource parent) throws ResourceCreationException {
        return createResource(new FQName(fqname), id, parent, null);
    }

    @Override
    public Resource createResource(@NotNull String fqname, String id) throws ResourceCreationException {
        return createResource(new FQName(fqname), id, null, null);
    }

    @Override
    public Resource createResource(@NotNull String fqname, @Nullable Collection<ResourceMatcher> importPaths) throws ResourceCreationException {
        return createResource(new FQName(fqname), null, null, importPaths);
    }

    @Override
    public Resource createResource(@NotNull FQName fqname) throws ResourceCreationException {
        return createResource(fqname, null, null, null);
    }

    @Override
    public Resource createResource(@NotNull String fqname) throws ResourceCreationException {
        return createResource(new FQName(fqname), null, null, null);
    }

    @Override
    public Resource createResource(@NotNull FQName fqname, @Nullable Resource parent) throws ResourceCreationException {
        return createResource(fqname, null, parent, null);
    }

    @Override
    public Resource createResource(@NotNull String fqname, @Nullable Resource parent) throws ResourceCreationException {
        return createResource(new FQName(fqname), null, parent, null);
    }

    @Override
    public Resource createResource(@NotNull Object obj) throws ResourceCreationException {
        Class<?> clazz = obj.getClass();
        STResource annotation = clazz.getAnnotation(STResource.class);
        if (annotation == null) {
            throw new ResourceCreationException("Attempted to create resource using java class which is not annotated with @STResource: " + obj.getClass().getName());
        }
        try {
            ResourceDefinition resourceDefinition = JavaResourceDefinitionFactory.create(clazz, null);
            registerResourceDefinition(resourceDefinition);
            return createResource(resourceDefinition.getFQName());
        } catch (InvalidResourceDefinitionException e) {
            throw new ResourceCreationException(e.getMessage(), e);
        }
    }

    @Override
    public Resource createResource(@NotNull String fqname, @Nullable Collection<ResourceMatcher> importPaths, @Nullable Resource parent) throws ResourceCreationException {
        return createResource(new FQName(fqname), null, parent, importPaths);
    }

    @Override
    @NotNull
    public List<Resource> findResources(@NotNull String query) throws InvalidQueryException {
        return new ResourceQuery(context, query, context.currentResource()).find(resources);
    }

    @Override
    @NotNull
    public List<Resource> findResources(@NotNull String query, @Nullable Resource baseResource) throws InvalidQueryException {
        return new ResourceQuery(context, query, baseResource).find(resources);
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
    @Override
    public ResourceDefinition findResourceDefinition(FQName name, @Nullable Collection<ResourceMatcher> importPaths) throws MultipleResourceMatchException, ResourceNotFoundException, ResourceCreationException {
        rlock();
        try {
            ResourceFinder rfinder = new ResourceFinder(name, importPaths);
            if (!rfinder.found()) {
                // dynamically loading matching DSL file
                if (name.getPkg() != null) {
                    dynaLoad(name.getPkg(), name.getName());
                } else if (importPaths != null) {
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

    @Override
    public Resource findResourcesByUid(String uid) {
        return resourcesUidIndex.get(uid);
    }

    // Resource registration

    @Override
    public void registerJavaResource(Class<?> clazz) throws InvalidResourceDefinitionException {
        FQName fqName = new FQName(clazz);
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
        registerResourceDefinition(JavaResourceDefinitionFactory.create(clazz, fqname));
    }


    @Override
    public void registerResourceDefinitions(Collection<ResourceDefinition> resourceDefinitions) throws InvalidResourceDefinitionException {
        for (ResourceDefinition def : resourceDefinitions) {
            registerResourceDefinition(def);
        }
    }

    @Override
    public void registerResourceDefinition(ResourceDefinition resourceDefinition) throws InvalidResourceDefinitionException {
        wlock();
        try {
            ResourceDefinition existing = findResourceDefinition(resourceDefinition.getFQName());
            if (existing != null) {
                existing.merge(resourceDefinition);
            } else {
                resourceDefinition.validate();
                resourceDefinitionsFQNIndex.put(resourceDefinition.getFQName(), resourceDefinition);
                resourceDefinitions.add(resourceDefinition);
            }
        } finally {
            wulock();
        }
    }

    private ResourceDefinition findResourceDefinition(FQName fqname) {
        rlock();
        try {
            return resourceDefinitionsFQNIndex.get(fqname);
        } finally {
            rulock();
        }
    }

    @Override
    public void close() {
        wlock();
        try {
            for (ResourceDefinition factory : resourceDefinitions) {
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

    /**
     * Calling this method will prepareForExecution all indexes, resolve all references, and re-order the resources based on dependencies
     */
    @Override
    public void prepareForExecution() throws InvalidDependencyException, MultipleUniqueResourcesFoundException {
        wlock();
        try {
            // Validate resource uniqueness
            HashSet<FQName> globalUnique = new HashSet<>();
            SetHashMap<Host, FQName> hostUnique = new SetHashMap<>();
            for (Resource resource : resources) {
                UniqueScope uniqueScope = resource.getDefinition().getUniqueScope();
                if (uniqueScope != null) {
                    switch (uniqueScope) {
                        case GLOBAL:
                            if (globalUnique.contains(resource.getType())) {
                                throw new MultipleUniqueResourcesFoundException(resource);
                            } else {
                                globalUnique.add(resource.getType());
                            }
                            break;
                        case HOST:
                            HashSet<FQName> set = hostUnique.get(resource.host());
                            if (set.contains(resource.getType())) {
                                throw new MultipleUniqueResourcesFoundException(resource);
                            } else {
                                set.add(resource.getType());
                            }
                            break;
                        default:
                            throw new RuntimeException("BUG! Unknown resource scope "+uniqueScope);
                    }
                }
            }
            // add dependency on parent if missing
            for (Resource resource : new ArrayList<>(resources)) {
                Resource parent = resource.getParent();
                if (parent != null && !resource.getDependencies().contains(parent)) {
                    resource.addDependency(parent);
                }
            }
            // mandatory children resolution
            resolveDependencies(true);
            // build parent/child map
            parentChildIndex = new HashMap<>();
            for (Resource resource : resources) {
                if (resource.getParent() != null) {
                    getChildrensInternalList(resource.getParent()).add(resource);
                }
            }
            for (Resource resource : resources) {
                // make dependent on resource if dependent on parent (childrens excluded from this rule)
                for (Resource dep : resource.getDependencies()) {
                    if (resource.getParent() == null || !resource.getParent().equals(dep)) {
                        makeDependentOnChildren(resource, dep);
                    }
                }
                // Sort resource's actions
                resource.sortActions();
            }
            // Sort according to dependencies
            ResourceSorter.sort(resources);
        } finally {
            wulock();
        }
    }

    @Override
    public void resolveDependencies(boolean strict) throws InvalidDependencyException {
        wlock();
        try {
            for (Resource resource : resources) {
                resource.dependencies.clear();
                resource.dependents.clear();
                handleDependencyAttr(resource, "before");
                handleDependencyAttr(resource, "after");
            }
            for (ManyToManyResourceDependency m2mDependency : m2mDependencies) {
                o2mDependencies.addAll(m2mDependency.resolve(context));
            }
            m2mDependencies.clear();
            for (OneToManyResourceDependency dependency : o2mDependencies) {
                Resource old = resourceScope.get();
                resourceScope.set(dependency.getOrigin());
                dependency.resolve(context);
                if (old != null) {
                    resourceScope.set(old);
                } else {
                    resourceScope.remove();
                }
                Resource origin = dependency.getOrigin();
                for (Resource target : dependency.getTargets()) {
                    if (target.getState() == Resource.State.FAILED) {
                        origin.setState(Resource.State.FAILED);
                    }
                    origin.dependencies.add(target);
                    target.dependents.add(origin);
                }
            }
        } finally {
            wulock();
        }
    }

    private void handleDependencyAttr(Resource resource, String attr) throws InvalidDependencyException {
        String value = resource.get(attr);
        if (isNotEmpty(value)) {
            try {
                List<Resource> deps = context.findResources(value, resource.getParent());
                if (deps.isEmpty()) {
                    throw new InvalidDependencyException("resource " + resource + " " + value + " attribute does not match any resources: " + value);
                }
                ManyToManyResourceDependency dependency;
                if (attr.equals("after")) {
                    dependency = new ManyToManyResourceDependency(resource, deps);
                } else if (attr.equals("before")) {
                    dependency = new ManyToManyResourceDependency(deps, resource);
                } else {
                    throw new RuntimeException("BUG: Invalid dependency attribute " + attr);
                }
                addDependency(dependency);
            } catch (InvalidQueryException e) {
                throw new InvalidDependencyException("Resource " + resource + " has an invalid " + attr + " attribute: " + value);
            }
        }
    }

    @Override
    public Set<ResourceDependency> getDependencies() {
        synchronized (m2mDependencies) {
            HashSet<ResourceDependency> set = new HashSet<>();
            set.addAll(o2mDependencies);
            set.addAll(m2mDependencies);
            return Collections.unmodifiableSet(set);
        }
    }

    @Override
    public void addDependency(ResourceDependency dependency) {
        synchronized (m2mDependencies) {
            if (dependency instanceof ManyToManyResourceDependency) {
                m2mDependencies.add((ManyToManyResourceDependency) dependency);
            } else {
                o2mDependencies.add((OneToManyResourceDependency) dependency);
            }
        }
    }

    @Override
    public void removeDependency(ResourceDependency dependency) {
        synchronized (m2mDependencies) {
            if (dependency instanceof ManyToManyResourceDependency) {
                m2mDependencies.remove(dependency);
            } else {
                o2mDependencies.remove(dependency);
            }
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
        private ResourceDefinition fac;

        public ResourceFinder(FQName name, Collection<ResourceMatcher> importPaths) throws MultipleResourceMatchException {
            this.name = name;
            if (name.getPkg() != null) {
                set(resourceDefinitionsFQNIndex.get(name));
            } else {
                for (ResourceDefinition resourceDefinition : resourceDefinitions) {
                    if (ResourceMatcher.matchAll(importPaths, resourceDefinition.getFQName()) && resourceDefinition.getName().equals(name.getName())) {
                        set(resourceDefinition);
                    }
                }
            }
        }

        public void set(ResourceDefinition newMatch) throws MultipleResourceMatchException {
            if (fac != null) {
                throw new MultipleResourceMatchException("Found more than one match for " + name.getName() + ": " + fac.getFQName() + " and " + newMatch.getFQName().toString());
            } else {
                fac = newMatch;
            }
        }

        public boolean found() {
            return fac != null;
        }

        public ResourceDefinition getMatch() throws ResourceNotFoundException {
            if (fac == null) {
                throw new ResourceNotFoundException("Unable to find resource " + name);
            } else {
                return fac;
            }
        }
    }
}
