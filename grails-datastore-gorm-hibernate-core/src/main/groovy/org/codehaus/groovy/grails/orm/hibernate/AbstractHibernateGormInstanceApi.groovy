/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.orm.hibernate

import grails.core.GrailsDomainClassProperty
import grails.core.support.proxy.ProxyHandler
import grails.util.GrailsClassUtils
import grails.validation.CascadingValidator
import grails.validation.DeferredBindingActions
import grails.validation.ValidationException
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.codehaus.groovy.grails.orm.hibernate.proxy.SimpleHibernateProxyHandler
import org.codehaus.groovy.grails.orm.hibernate.support.HibernateRuntimeUtils
import org.codehaus.groovy.grails.orm.hibernate.validation.AbstractPersistentConstraint
import org.grails.core.lifecycle.ShutdownOperations
import org.grails.datastore.gorm.GormInstanceApi
import org.grails.datastore.mapping.engine.event.ValidationEvent
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ToOne
import org.hibernate.*
import org.springframework.beans.BeanWrapperImpl
import org.springframework.beans.InvalidPropertyException
import org.springframework.dao.DataAccessException
import org.springframework.validation.Errors
import org.springframework.validation.Validator


/**
 * Abstract extension of the {@link GormInstanceApi} class that provides common logic shared by Hibernate 3 and Hibernate 4
 *
 * @author Graeme Rocher
 * @param < D >
 */
@CompileStatic
abstract class AbstractHibernateGormInstanceApi<D> extends GormInstanceApi<D> {
    private static final String ARGUMENT_VALIDATE = "validate";
    private static final String ARGUMENT_DEEP_VALIDATE = "deepValidate";
    private static final String ARGUMENT_FLUSH = "flush";
    private static final String ARGUMENT_INSERT = "insert";
    private static final String ARGUMENT_MERGE = "merge";
    private static final String ARGUMENT_FAIL_ON_ERROR = "failOnError";

    protected static final Object[] EMPTY_ARRAY = []
    /**
     * When a domain instance is saved without validation, we put it
     * into this thread local variable. Any code that needs to know
     * whether the domain instance should be validated can just check
     * the value. Note that this only works because the session is
     * flushed when a domain instance is saved without validation.
     */
    static ThreadLocal<Set<Integer>> disableAutoValidationForThreadLocal = new ThreadLocal<Set<Integer>>()
    static final ThreadLocal<Boolean> insertActiveThreadLocal = new ThreadLocal<Boolean>()

    static {
        ShutdownOperations.addOperation(new Runnable() {
            public void run() {
                AbstractHibernateGormInstanceApi.disableAutoValidationForThreadLocal.remove()
                AbstractHibernateGormInstanceApi.insertActiveThreadLocal.remove()
            }
        });
    }

    private static Set<Integer> getDisableAutoValidationFor() {
        Set<Integer> identifiers = AbstractHibernateGormInstanceApi.disableAutoValidationForThreadLocal.get()
        if (identifiers == null)
            AbstractHibernateGormInstanceApi.disableAutoValidationForThreadLocal.set(identifiers = new HashSet<Integer>())
        return identifiers;
    }

    public static boolean isAutoValidationDisabled(Object obj) {
        Set<Integer> identifiers = getDisableAutoValidationFor()
        return obj != null && identifiers.contains(System.identityHashCode(obj))
    }

    public static void clearDisabledValidations(Object obj) {
        getDisableAutoValidationFor().remove(System.identityHashCode(obj))
    }

    public static void clearDisabledValidations() {
        getDisableAutoValidationFor().clear();
    }

    protected SessionFactory sessionFactory
    protected ClassLoader classLoader
    protected IHibernateTemplate hibernateTemplate
    protected ProxyHandler proxyHandler = new SimpleHibernateProxyHandler()
    Map config = Collections.emptyMap()

    protected AbstractHibernateGormInstanceApi(Class<D> persistentClass, AbstractHibernateDatastore datastore, ClassLoader classLoader, IHibernateTemplate hibernateTemplate) {
        super(persistentClass, datastore)
        this.classLoader = classLoader
        sessionFactory = datastore.getSessionFactory()
        this.hibernateTemplate = hibernateTemplate
    }



    @Override
    D save(D target, Map arguments) {

        PersistentEntity domainClass = persistentEntity
        DeferredBindingActions.runActions()
        boolean shouldFlush = shouldFlush(arguments)
        boolean shouldValidate = shouldValidate(arguments, persistentEntity)

        HibernateRuntimeUtils.autoAssociateBidirectionalOneToOnes(domainClass, target)

        if (shouldValidate) {
            Validator validator = datastore.mappingContext.getEntityValidator(domainClass)

            Errors errors = HibernateRuntimeUtils.setupErrorsProperty(target)

            if (validator) {
                datastore.applicationContext?.publishEvent new ValidationEvent(datastore, target)

                boolean deepValidate = true
                if (arguments?.containsKey(ARGUMENT_DEEP_VALIDATE)) {
                    deepValidate = GrailsClassUtils.getBooleanFromMap(ARGUMENT_DEEP_VALIDATE, arguments);
                }

                AbstractPersistentConstraint.sessionFactory.set(sessionFactory)
                try {
                    if (deepValidate && (validator instanceof CascadingValidator)) {
                        ((CascadingValidator)validator).validate target, errors, deepValidate
                    }
                    else {
                        validator.validate target, errors
                    }
                }
                finally {
                    AbstractPersistentConstraint.sessionFactory.remove()
                }

                if (errors.hasErrors()) {
                    handleValidationError(domainClass,target,errors);
                    if (shouldFail(arguments)) {
                        throw new ValidationException("Validation Error(s) occurred during save()", errors)
                    }
                    return null
                }

                setObjectToReadWrite(target);
            }
        }

        // this piece of code will retrieve a persistent instant
        // of a domain class property is only the id is set thus
        // relieving this burden off the developer
        autoRetrieveAssocations domainClass, target

        if (!shouldValidate) {
            Set<Integer> identifiers = getDisableAutoValidationFor()
            identifiers.add System.identityHashCode(target)
        }

        if (shouldInsert(arguments)) {
            return performInsert(target, shouldFlush)
        }
        else if(shouldMerge(arguments)) {
            return performMerge(target, shouldFlush)
        }
        else {
            return performSave(target, shouldFlush)
        }
    }

    @Override
    D merge(D instance, Map params) {
        Map args = new HashMap(params)
        args[ARGUMENT_MERGE] = true
        return save(instance, params)
    }

    @Override
    D insert(D instance, Map params) {
        Map args = new HashMap(params)
        args[ARGUMENT_INSERT] = true
        return save(instance, args)
    }

    @Override
    void discard(D instance) {
        hibernateTemplate.evict instance
    }

    @Override
    void delete(D instance, Map params = Collections.emptyMap()) {
        boolean flush = shouldFlush(params)
        try {
            hibernateTemplate.execute { Session session ->
                session.delete instance
                if(flush) {
                    session.flush()
                }
            }
        }
        catch (DataAccessException e) {
            try {
                hibernateTemplate.execute { Session session ->
                    session.flushMode = FlushMode.MANUAL
                }
            }
            finally {
                throw e
            }
        }
    }

    @Override
    boolean isAttached(D instance) {
        hibernateTemplate.contains instance
    }

    @Override
    boolean instanceOf(D instance, Class cls) {
        return proxyHandler.unwrapIfProxy(instance) in cls
    }

    @Override
    D lock(D instance) {
        hibernateTemplate.lock(instance, LockMode.PESSIMISTIC_WRITE)
    }

    @Override
    D attach(D instance) {
        hibernateTemplate.lock(instance, LockMode.NONE)
        return instance
    }

    @Override
    D refresh(D instance) {
        hibernateTemplate.refresh(instance)
        return instance
    }

    protected D performSave(final D target, final boolean flush) {
        hibernateTemplate.execute { Session session ->
            session.saveOrUpdate target
            if (flush) {
                flushSession session
            }
            return target
        }
    }

    protected D performMerge(final D target, final boolean flush) {
        hibernateTemplate.execute { Session session ->
            Object merged = session.merge(target)
            session.lock(merged, LockMode.NONE);
            if (flush) {
                flushSession session
            }
            return merged
        }
    }

    protected D performInsert(final D target, final boolean shouldFlush) {
        hibernateTemplate.execute { Session session ->
            try {
                markInsertActive()
                session.save target
                if (shouldFlush) {
                    flushSession session
                }
                return target
            } finally {
                resetInsertActive()
            }

        }
    }

    protected void flushSession(Session session) throws HibernateException {
        try {
            session.flush()
        } catch (HibernateException e) {
            // session should not be flushed again after a data acccess exception!
            session.setFlushMode FlushMode.MANUAL
            throw e
        }
    }
    /**
     * Performs automatic association retrieval
     * @param entity The domain class to retrieve associations for
     * @param target The target object
     */
    @SuppressWarnings("unchecked")
    private void autoRetrieveAssocations(PersistentEntity entity, Object target) {
        def bean = new BeanWrapperImpl(target)
        IHibernateTemplate t = this.hibernateTemplate
        for (PersistentProperty prop in entity.associations) {
            if(prop instanceof ToOne) {
                ToOne toOne = (ToOne)prop
                def propValue = bean.getPropertyValue(prop.name)
                if (propValue == null || t.contains(propValue)) {
                    continue
                }

                PersistentEntity otherSide = toOne.associatedEntity
                if (otherSide == null) {
                    continue
                }

                def propBean = new BeanWrapperImpl(propValue)
                try {
                    def id = (Serializable)propBean.getPropertyValue(otherSide.identity.name);
                    if (id) {
                        final Object propVal = t.get(prop.type, id)
                        if (propVal) {
                            bean.setPropertyValue prop.name, propVal
                        }
                    }
                }
                catch (InvalidPropertyException ipe) {
                    // property is not accessable
                }
            }

        }
    }

    /**
     * Checks whether validation should be performed
     * @return true if the domain class should be validated
     * @param arguments  The arguments to the validate method
     * @param domainClass The domain class
     */
    private boolean shouldValidate(Map arguments, PersistentEntity entity) {
        if (!entity) {
            return false
        }

        if (arguments.containsKey(ARGUMENT_VALIDATE)) {
            return GrailsClassUtils.getBooleanFromMap(ARGUMENT_VALIDATE, arguments)
        }
        return true
    }

    private boolean shouldInsert(Map arguments) {
        GrailsClassUtils.getBooleanFromMap(ARGUMENT_INSERT, arguments)
    }

    private boolean shouldMerge(Map arguments) {
        GrailsClassUtils.getBooleanFromMap(ARGUMENT_MERGE, arguments)
    }


    protected boolean shouldFlush(Map map) {
        if (map?.containsKey(ARGUMENT_FLUSH)) {
            return GrailsClassUtils.getBooleanFromMap(ARGUMENT_FLUSH, map)
        }
        return config?.autoFlush instanceof Boolean ? config.autoFlush : false
    }

    protected boolean shouldFail(Map map) {
        if (map?.containsKey(ARGUMENT_FAIL_ON_ERROR)) {
            return GrailsClassUtils.getBooleanFromMap(ARGUMENT_FAIL_ON_ERROR, map)
        }
        return config?.get(ARGUMENT_FAIL_ON_ERROR) instanceof Boolean ? GrailsClassUtils.getBooleanFromMap(ARGUMENT_FAIL_ON_ERROR, config) : false
    }

    /**
     * Sets the flush mode to manual. which ensures that the database changes are not persisted to the database
     * if a validation error occurs. If save() is called again and validation passes the code will check if there
     * is a manual flush mode and flush manually if necessary
     *
     * @param domainClass The domain class
     * @param target The target object that failed validation
     * @param errors The Errors instance  @return This method will return null signaling a validation failure
     */
    protected Object handleValidationError(PersistentEntity entity, final Object target, Errors errors) {
        // if a validation error occurs set the object to read-only to prevent a flush
        setObjectToReadOnly target
        if (entity) {
            for (Association association in entity.associations) {
                if (association instanceof ToOne) {
                    if(proxyHandler.isInitialized(target, association.name)) {
                        def bean = new BeanWrapperImpl(target)
                        def propertyValue = bean.getPropertyValue(association.name)
                        if (propertyValue != null) {
                            setObjectToReadOnly propertyValue
                        }
                    }
                }
            }
        }
        setErrorsOnInstance target, errors
        return null
    }

    /**
     * Sets the target object to read-only using the given SessionFactory instance. This
     * avoids Hibernate performing any dirty checking on the object
     *
     *
     * @param target The target object
     * @param sessionFactory The SessionFactory instance
     */
    public void setObjectToReadOnly(Object target) {
        hibernateTemplate.execute { Session session ->
            if (session.contains(target) && proxyHandler.isInitialized(target)) {
                target = proxyHandler.unwrapIfProxy(target)
                session.setReadOnly target, true
                session.flushMode = FlushMode.MANUAL
            }
        }
    }
    /**
     * Sets the target object to read-write, allowing Hibernate to dirty check it and auto-flush changes.
     *
     * @see #setObjectToReadOnly(Object)
     *
     * @param target The target object
     * @param sessionFactory The SessionFactory instance
     */
    public abstract void setObjectToReadWrite(Object target)

    /**
     * Associates the Errors object on the instance
     *
     * @param target The target instance
     * @param errors The Errors object
     */
    @CompileDynamic
    protected void setErrorsOnInstance(Object target, Errors errors) {
        target."$GrailsDomainClassProperty.ERRORS" = errors
    }

    /**
     * Prevents hitting the database for an extra check if the row exists in the database.
     *
     * ThreadLocal is used to pass the "insert:true" information to Hibernate.
     *
     * @see org.hibernate.event.def.AbstractSaveEventListener#getAssumedUnsaved()
     */
    public static Boolean getAssumedUnsaved() {
        return insertActiveThreadLocal.get();
    }

    /**
     * Called by org.codehaus.groovy.grails.orm.hibernate.metaclass.SavePersistentMethod's performInsert
     * to set a ThreadLocal variable that determines the value for getAssumedUnsaved().
     */
    public static void markInsertActive() {
        insertActiveThreadLocal.set(Boolean.TRUE);
    }

    /**
     * Clears the ThreadLocal variable set by markInsertActive().
     */
    public static void resetInsertActive() {
        insertActiveThreadLocal.remove();
    }

    /**
     * Increments the entities version number in order to force an update
     * @param target The target entity
     */
    @CompileDynamic
    protected void incrementVersion(Object target) {
        if (target.hasProperty(GrailsDomainClassProperty.VERSION)) {
            Object version = target."${GrailsDomainClassProperty.VERSION}"
            if (version instanceof Long) {
                target."${GrailsDomainClassProperty.VERSION}" = ++((Long)version)
            }
        }
    }

}
