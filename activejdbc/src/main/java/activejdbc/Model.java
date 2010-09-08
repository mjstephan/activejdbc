/*
Copyright 2009-2010 Igor Polevoy 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/


package activejdbc;

import activejdbc.associations.*;
import activejdbc.cache.QueryCache;
import activejdbc.validation.*;
import static javalite.common.Util.blank;

import java.sql.*;
import java.math.BigDecimal;
import java.util.*;

import static javalite.common.Inflector.*;
import javalite.common.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public abstract class Model extends CallbackSupport{

    private final static Logger logger = LoggerFactory.getLogger(Model.class);
    private Map<String, Object> attributes = new HashMap<String, Object>();
    private boolean frozen = false;
    private MetaModel metaModelLocal;
    private Map<Class, Model> cachedParents = new HashMap<Class, Model>();

    protected Map<String, String> errors;
    
    static{
        Registry.instance().init();
    }

    protected Model() {
        errors = new HashMap<String, String>();
    }

    public static MetaModel getMetaModel() {
        return Registry.instance().getMetaModel(getTableName());
    }

    /**
     * Hydrates a this instance of model from a map. Only picks values from a map that match
     * this instance's attribute names, while ignoring the others.
     *
     * @param attributes map containing values for this instance.
     */
    protected  void hydrate(Map attributes) {

        List<String> attributeNames = getMetaModelLocal().getAttributeNamesSkipId();
        this.attributes = new HashMap<String, Object> ();

        Object id = attributes.get(getMetaModelLocal().getIdNameUpper());
        if (id == null) {
            id = attributes.get(getMetaModelLocal().getIdNameLower());
        }

        this.attributes.put(getMetaModelLocal().getIdNameLower(), id);

        for (String attrName : attributeNames) {
            Object value = attributes.get(attrName.toLowerCase());
            if (value == null) {
                value = attributes.get(attrName.toUpperCase());
            }

            //it is necessary to cache contents of a clob, because if a clob instance itself s cached, and accessed later,
            //it will not be able to connect back to that same connection from which it came.
            //This is only important for cached models. This will allocate a ton of memory if Clobs are large.
            //Should the Blob behavior be the same?
            //TODO: write about this in future tutorial
            if(value instanceof Clob && getMetaModelLocal().cached()){
                this.attributes.put(attrName.toLowerCase(), Converter.toString(value));
            }else{
                this.attributes.put(attrName.toLowerCase(), value);
            }
        }
    }

    //TODO: add typed setters corresponding to typed getters    
    public void setDate(String name, java.util.Date date) {
        if (date == null) {
            set(name, null);
        } else {
            set(name, new java.sql.Date(date.getTime()));
        }
    }

    public void setTS(String name, java.util.Date date) {
        if(date == null) {
            set(name, null);
        } else {
            set(name, new java.sql.Timestamp(date.getTime()));
        }
    }

    /**
     * Sets values for this model instance. The squence of values must correspond to sequence of names.
     *
     * @param attributeNames names of attributes.
     * @param values values for this instance.
     */
    public void set(String[] attributeNames, Object[] values) {
        if (attributeNames == null || values == null || attributeNames.length != values.length) {
            throw new IllegalArgumentException("must pass non-null arrays of equal length");
        }

        for (int i = 0; i < attributeNames.length; i++) {
            set(attributeNames[i], values[i]);
        }
    }

    public Model set(String attribute, Object value) {
        if(attribute.equalsIgnoreCase("created_at")) throw new IllegalArgumentException("cannot set 'created_at'");

        getMetaModelLocal().checkAttributeOrAssociation(attribute);

        if (attributes.get(attribute.toLowerCase()) != null) {
            attributes.put(attribute.toLowerCase(), value);
        }

        if (attributes.get(attribute.toUpperCase()) != null) {
            attributes.put(attribute.toUpperCase(), value);
        }

        attributes.put(attribute, value);
        return this;
    }

    /**
     * Will return true if this  instance is frozen, false otherwise.
     * A frozen instance cannot use used, as it has no relation to a record in table.
     *
     * @return true if this  instance is frozen, false otherwise.
     */
    public boolean isFrozen(){
        return frozen;
    }

    /**
     * returns true if this is a new instance, not saved yet to DB, false otherwise.
     *
     * @return true if this is a new instance, not saved yet to DB, false otherwise
     */
    public boolean isNew(){
        return getId() == null;
    }


    /**
     * Synonym for {@link #isFrozen()}. if(m.frozen()) seems to read better than classical Java convention.
     *
     * @return true if this  instance is frozen, false otherwise.
     */
    public boolean frozen(){
        return isFrozen();
    }

    /**
     * Deletes a single table record represented by this instance. This method assumes that a corresponding table
     * has only one record this the ID of this instance.
     * After deletion, this instance becomes {@link #frozen()} and cannot be used anymore until {@link #thaw()} is called.
     *
     * @return true if a record was deleted, false if not.
     */
    public boolean delete() {
        fireBeforeDelete(this);
        boolean result;
        if( 1 == new DB(getMetaModelLocal().getDbName()).exec("DELETE FROM " + getMetaModelLocal().getTableName()
                + " WHERE " + getMetaModelLocal().getIdName() + "=?", getId())) {
            frozen = true;
            if(getMetaModelLocal().cached()){
                QueryCache.instance().purgeTableCache(getMetaModelLocal().getTableName());
            }
            purgeEdges();
            result = true;
        }
        else{
            result =  false;
        }
        fireAfterDelete(this);
        return result;
    }

    /**
     * Deletes current model and all one to many associations. This is not a high performance method, as it will
     * load every row into a model instance before deleting, effectively calling (N + 1) per table queries to the DB, one to select all
     * the associated records (per table), and one delete statement per record. Use it for small data sets.
     * It will follow associations of children and their associations too.
     */
    public void deleteCascade(){

        List<Association> one2manies = getMetaModelLocal().getOneToManyAssociations();
        for (Association association : one2manies) {
            String targetTableName = association.getTarget();
            Class c = Registry.instance().getModelClass(targetTableName);
            if(c == null)// this model is probably not defined as a class, but the table exists!
            {
                logger.error("ActiveJDBC WARNING: failed to find a model class for: " + targetTableName + ", maybe model is not defined for this table?" +
                        " There might be a risk of running into integrity constrain violation if this model is not defined.");
            }
            else{
                List<Model> dependencies = getAll(c);
                for (Model model : dependencies) {
                    model.deleteCascade();
                }
            }
        }
        delete();
    }


    /**
     * Deletes some records from associated table. This method does not follow any associations.
     * If this model has one to many associations, you might end up with either orphan records in child
     * tables, or run into integrity constraint violations. However, this method if very efficient as it deletes all records
     * in one shot, without pre-loading them.
     * This method also has a side-effect: it will not mark loaded instances corresponding to deleted records as "frozen".
     * This means that such an instance would allow calling save() and saveIt() methods resulting DB errors, as you
     * would be attempting to update phantom records. 
     *
     *
     * @param query narrows which records to delete. Example: <pre>"last_name like '%sen%'"</pre>.
     * @param params   (optional) - list of parameters if a query is parametrized.
     * @return number od deleted records.
     */
    public static int delete(String query, Object... params) {
        MetaModel metaModel = getMetaModel();
        int count =  params == null || params.length == 0? new DB(metaModel.getDbName()).exec("DELETE FROM " + metaModel.getTableName() + " WHERE " + query) :
        new DB(metaModel.getDbName()).exec("DELETE FROM " + metaModel.getTableName() + " WHERE " + query, params);
        if(metaModel.cached()){
            QueryCache.instance().purgeTableCache(metaModel.getTableName());
        }
        purgeEdges();
        return count;
    }

    /**
     * Returns true if record corresponding to the id passed exists in the DB.
     *
     * @param id id in question.
     * @return true if corresponding record exists in DB, false if it does not.
     */
    public static boolean exists(Object id){
        MetaModel metaModel = getMetaModel();
        return null != new DB(metaModel.getDbName()).firstCell("SELECT " + metaModel.getIdName() + " FROM " + metaModel.getTableName()
                + " WHERE " + metaModel.getIdName() + " = ?", id);
    }

    /**
     * Deletes all records from associated table. This methods does not take associations into account. 
     *
     * @return number of records deleted.
     */
    public static int deleteAll() {
        MetaModel metaModel = getMetaModel();
        int count = new DB(metaModel.getDbName()).exec("DELETE FROM " + metaModel.getTableName());
        if(metaModel.cached()){
            QueryCache.instance().purgeTableCache(metaModel.getTableName());
        }

        purgeEdges();
        return count;
    }

    /**
     * Updates records associated with this model.
     *
     * This example :
     * <pre>
     *  Employee.update("bonus = ?", "years_at_company > ?", "5", "10");
     * </pre
     * In this example, employees who worked for more than 10 years, get a bonus of 5% (not so generous :)).
     *
     *
     * @param updates - what needs to be updated.
     * @param conditions specifies which records to update. If this argument is <code>null</code>, all records in table will be updated.
     * In such cases, use a more explicit {@link #updateAll(String, Object...)} method.
     * @param params list of parameters for both updates and conditions. Applied in the same order as in the arguments,
     * updates first, then conditions.
     * @return number of updated records.
     */
    public static int update(String updates, String conditions, Object ... params) {

        //TODO: validate that the number of question marks is the same as number of parameters

        MetaModel metaModel = getMetaModel();
        Object []allParams;
        if(metaModel.hasAttribute("updated_at")){
            updates = "updated_at = ?, " + updates;
            allParams = new Object[params.length + 1];
            System.arraycopy(params, 0, allParams, 1, params.length);

            allParams[0] = new Timestamp(System.currentTimeMillis());
        }else{
            allParams = params;
        }
        String sql = "UPDATE " + metaModel.getTableName() + " SET " + updates + ((conditions != null)?" WHERE " + conditions:"");
        int count = new DB(metaModel.getDbName()).exec(sql, allParams);
        if(metaModel.cached()){
            QueryCache.instance().purgeTableCache(metaModel.getTableName());
        }
        return count;
    }


    /**
     * Updates all records associated with this model.
     *
     * This example :
     * <pre>
     *  Employee.updateAll("bonus = ?", "10");
     * </pre
     * In this example, all employees get a bonus of 10%.
     *
     *
     * @param updates - what needs to be updated.
     * @param params list of parameters for both updates and conditions. Applied in the same order as in the arguments,
     * updates first, then conditions.
     * @return number of updated records.
     */
    public static int updateAll(String updates, Object ... params) {
        return update(updates, null, params);
    }

    /**
     * Returns all values of the model with all attribute names converted to lower case,
     * regardless how these names came from DB. This method is a convenience
     * method for displaying values on web pages.
     *
     * <p/>
     * If {@link activejdbc.LazyList#include(Class[])} method was used, and this
     * model belongs to a parent (as in many to one relationship), then the parent
     * will be eagerly loaded and also converted to a map. Parents' maps are keyed in the
     * returned map by underscored name of a parent model class name.
     * <p/>
     * For example, if this model were <code>Address</code>
     * and a parent is <code>User</code> (and user has many addresses), then the resulting map would
     * have all the attributes of the current table and another map representing a parent user with a
     * key "user" in current map.
     *
     * @return all values of the model with all attribute names converted to lower case.
     */
    public Map<String, Object> toMap(){
        Map<String, Object> retVal = new HashMap<String, Object>();
        for (String key : attributes.keySet()) {
            retVal.put(key.toLowerCase(), attributes.get(key));
        }
        for(Class parentClass: cachedParents.keySet()){
            retVal.put(underscore(shortName(parentClass.getName())), cachedParents.get(parentClass).toMap());
        }

        for(Class childClass: cachedChildren.keySet()){
            List<Model> children = cachedChildren.get(childClass);

            List<Map> childMaps = new ArrayList<Map>(children.size());
            for(Model child:children){
                childMaps.add(child.toMap());
            }
            retVal.put(pluralize(underscore(shortName(childClass.getName()))), childMaps);
        }
        return retVal;
    }

    public void fromMap(Map map){
        hydrate(map);
    }


    /**
     * Returns parent of this model, assuming that this table represents a child.
     *
     * @param parentClass   class of a parent model.
     * @return instance of a parent of this instance in the "belongs to"  relationship.
     */
    public <T extends Model> T parent(Class<T> parentClass) {

        T cachedParent = (T)cachedParents.get(parentClass);
        if(cachedParent != null){
            return cachedParent;
        }
        MetaModel parentMM = Registry.instance().getMetaModel(parentClass);
        String parentTable = parentMM.getTableName();

        BelongsToAssociation ass = (BelongsToAssociation)getMetaModelLocal().getAssociationForTarget(parentTable, BelongsToAssociation.class);
        BelongsToPolymorphicAssociation assP = (BelongsToPolymorphicAssociation)getMetaModelLocal()
                .getAssociationForTarget(parentTable, BelongsToPolymorphicAssociation.class);

        String fkValue;
        if(ass != null){
            fkValue = getString(ass.getFkName());
        }else if(assP != null){
            fkValue = getString("parent_id");
            MetaModel trueMM = Registry.instance().getMetaModelByClassName(getString("parent_type"));
            if(trueMM != parentMM)
                throw new IllegalArgumentException("Wrong parent: '" + parentClass + "'. Actual parent type of this record is: '" + getString("parent_type") + "'");
        }else{
            throw new DBException("there is no association with table: " + parentTable);
        }

        String parentIdName = parentMM.getIdName();
        String query = getMetaModelLocal().getDialect().selectStarParametrized(parentTable, parentIdName);

        T parent;
        if(parentMM.cached()){
            parent = (T)QueryCache.instance().getItem(parentTable, query, new Object[]{fkValue});
            if(parent != null){
                return parent;
            }
        }

        List<Map> results = new DB(getMetaModelLocal().getDbName()).findAll(query, Integer.parseInt(fkValue));
        //expect only one result here
        if (results.size() == 0) { //ths could be covered by referential integrity constraint
            return null;
        } else {
            try {
                parent = parentClass.newInstance();
                parent.hydrate(results.get(0));
                if(parentMM.cached()){
                    QueryCache.instance().addItem(parentTable, query, new Object[]{fkValue}, parent);
                }
                return parent;
            } catch (Exception e) {
                throw new InitException(e.getMessage(), e);
            }
        }
    }

    protected void setParent(Model parent) {
        cachedParents.put(parent.getClass(), parent);
    }

    /**
     * Copies all attribute values (except for ID, created_at and updated_at) from this instance to the other.
     *
     * @param other target model.
     */
    public void copyTo(Model other) {
        if (!getMetaModelLocal().getTableName().equals(other.getMetaModelLocal().getTableName())) {
            throw new IllegalArgumentException("can only copy between the same types");
        }

        List<String> attrs = getMetaModelLocal().getAttributeNamesSkipGenerated();
        for (String name : attrs) {
            other.set(name, get(name));
        }
    }

    /**
     * Copies all attribute values (except for ID, created_at and updated_at) from this instance to the other.
     *
     * @param other target model.
     */
    public void copyFrom(Model other) {
        other.copyTo(this);
    }

    private MetaModel getMetaModelLocal(){
        if(metaModelLocal == null)
            metaModelLocal = getMetaModel();

        return metaModelLocal;
    }

    /**
     * Re-reads all attribute values from DB.
     * //TODO: test!
     */
    public void refresh() {
        Model fresh = findById(getId());
        fresh.copyTo(this);
    }

    /**
     * Returns a value for attribute. Besides returning direct attributes of this model, this method is also
     * aware of relationships and can return collections based on naming conventions. For example, if a model User has a
     * one to many relationship with a model Address, then the following code will work:
     * <pre>
     * Address address = ...;
     * User user = (User)address.get("user");
     * </pre>
     * Conversely, this will also work:
     * <pre> 
     * List<Address> addresses = (List<Address>)user.get("addresses");
     * </pre>
     *
     * The same would also work for many to many relationships:
     * <pre>
     * List<Doctor> doctors = (List<Doctor>)patient.get("doctors");
     * ...
     * List<Patient> patients = (List<Patient>)doctor.get("patients");
     * </pre>
     *
     * This methods will try to infer a name if a table by using {@link javalite.common.Inflector} to try to
     * convert it to singular and them plural form, an attempting to see if this model has an appropriate relationship
     * with another model, if one is found. This method of finding of relationships is best used in templating
     * technologies, such as JSPs. For standard cases, please use {@link #parent(Class)}, and {@link #getAll(Class)}.
     *
     *
     * @param attribute name of attribute.
     * @return value for attribute.
     */
    public Object get(String attribute) {
        if(frozen) throw new FrozenException(this);

        if(attribute == null) throw new IllegalArgumentException("attribute cannot be null");

        // NOTE: this is a workaround for JSP pages. JSTL in cases ${item.id} does not call the getId() method, instead
        //calls item.get("id"), considering that this is a map only!        
        if(attribute.equalsIgnoreCase("id")){
            String idName = getMetaModelLocal().getIdName();
            return attributes.get(idName.toLowerCase());
        }

        Object returnValue;
        if((returnValue = attributes.get(attribute.toLowerCase())) != null){
            return returnValue;
        }else if((returnValue = attributes.get(attribute.toUpperCase())) != null){
            return returnValue;
        }else if((returnValue = tryParent(attribute)) != null){
            return returnValue;
        }else if((returnValue = tryPolymorphicParent(attribute)) != null){
            return returnValue;
        }else if((returnValue = tryChildren(attribute)) != null){
            return returnValue;
        }else if((returnValue = tryPolymorphicChildren(attribute)) != null){
            return returnValue;
        }else if((returnValue = tryOther(attribute)) != null){
            return returnValue;
        }else{
            getMetaModelLocal().checkAttributeOrAssociation(attribute);
            return null;
        }
    }


    private Object tryPolymorphicParent(String parentTable){
        MetaModel parentMM = inferTargetMetaModel(parentTable);
        if(parentMM == null){
            return null;
        }else
            return getMetaModelLocal().hasAssociation(parentMM.getTableName(), BelongsToPolymorphicAssociation.class) ?
                parent(parentMM.getModelClass()): null;
    }

    private Object tryParent(String parentTable){
        MetaModel parentMM = inferTargetMetaModel(parentTable);
        if(parentMM == null){
            return null;
        }else
            return getMetaModelLocal().hasAssociation(parentMM.getTableName(), BelongsToAssociation.class) ?
                parent(parentMM.getModelClass()): null;
    }

    private Object tryPolymorphicChildren(String childTable){
        MetaModel childMM = inferTargetMetaModel(childTable);
        if(childMM == null){
            return null;
        }else
            return getMetaModelLocal().hasAssociation(childMM.getTableName(), OneToManyPolymorphicAssociation.class) ?
                getAll(childMM.getModelClass()): null;
    }

    private Object tryChildren(String childTable){
        MetaModel childMM = inferTargetMetaModel(childTable);
        if(childMM == null){
            return null;
        }else
            return getMetaModelLocal().hasAssociation(childMM.getTableName(), OneToManyAssociation.class) ?
                getAll(childMM.getModelClass()): null;
    }
    
    private Object tryOther(String otherTable){
        MetaModel otherMM = inferTargetMetaModel(otherTable);
        if(otherMM == null){
            return null;
        }else
            return getMetaModelLocal().hasAssociation(otherMM.getTableName(), Many2ManyAssociation.class) ?
                getAll(otherMM.getModelClass()): null;
    }

    private MetaModel inferTargetMetaModel(String targetTableName){
        String targetTable = singularize(targetTableName);
        MetaModel targetMM = Registry.instance().getMetaModel(targetTable);
        if(targetMM == null){
            targetTable = pluralize(targetTableName);
            targetMM = Registry.instance().getMetaModel(targetTable);
        }
        return targetMM != null? targetMM: null;
    }

    /**
     * Get any value as string.
     *
     * @param attribute name of attribute.
     * @return attribute value as string.
     */
    public String getString(String attribute) {
        Object value = get(attribute);
        return Converter.toString(value);
    }

    public BigDecimal getBigDecimal(String attribute) {
        return Converter.toBigDecimal(get(attribute));
    }

    public Integer getInteger(String attribute) {
        return Converter.toInteger(get(attribute));
    }

    public Long getLong(String attribute) {
        return Converter.toLong(get(attribute));
    }

    public Float getFloat(String attribute) {
        return Converter.toFloat(get(attribute));
    }

    public Timestamp getTimestamp(String attribute) {
        return Converter.toTimestamp(get(attribute));

    }

    public Double getDouble(String attribute) {
        return Converter.toDouble(get(attribute));
    }

    public java.sql.Date getDate(String attribute) {
        return Converter.toSqlDate(get(attribute));
    }

    public Boolean getBoolean(String attribute) {
        return Converter.toBoolean(get(attribute));
    }

    /**
     * This methods supports one to many as well as many to many relationships.
     * In case of one to many, the <code>clazz</code>  must be a class of a child model, and it will return a
     * collection of all children.
     * <p/>
     * In case of many to many, the <code>clazz</code>  must be a class of a another related model, and it will return a
     * collection of all related models.
     * //TODO: add ability to filter children too: getAll(Class<? extends Model> clazz, String query, Object params).orderBy(...)...
     *
     * @param clazz class of a child model for one to many, or class of another model, in case of many to many.
     * @return list of children in case of one to many, or list of other models, in case many to many.
     */

    public <T extends Model> LazyList<T> getAll(Class<T> clazz) {
        List<Model> children = cachedChildren.get(clazz);
        if(children != null){
            return (LazyList<T>) children;
        }

        String tableName = Registry.instance().getTableName(clazz);
        if(tableName == null) throw new IllegalArgumentException("table: " + tableName + " does not exist for model: " + clazz);

        return getAll(tableName);
    }

    
    private <T extends Model> LazyList<T> getAll(String targetTable) {
        //TODO: interesting thought: is it possible to have two associations of the same name, one to many and many to many? For now, say no.

        OneToManyAssociation oneToManyAssociation = (OneToManyAssociation)getMetaModelLocal().getAssociationForTarget(targetTable, OneToManyAssociation.class);
        Many2ManyAssociation manyToManyAssociation = (Many2ManyAssociation)getMetaModelLocal().getAssociationForTarget(targetTable, Many2ManyAssociation.class);

        OneToManyPolymorphicAssociation oneToManyPolymorphicAssociation = (OneToManyPolymorphicAssociation)getMetaModelLocal().getAssociationForTarget(targetTable, OneToManyPolymorphicAssociation.class);

        String subQuery;
        if (oneToManyAssociation != null) {
            subQuery = oneToManyAssociation.getFkName() + " = " + getId();
        } else if (manyToManyAssociation != null) {
            String targetId = Registry.instance().getMetaModel(targetTable).getIdName();
            subQuery = targetId + " IN ( SELECT " +
                    manyToManyAssociation.getTargetFkName() + " FROM " + manyToManyAssociation.getJoin() + " WHERE " +
                    manyToManyAssociation.getSourceFkName() + " = " + getId() + ")" ;
        } else if (oneToManyPolymorphicAssociation != null) {

            subQuery = "parent_id = " + getId() + " AND " + " parent_type = '" + getClass().getName() + "'";  
        } else {
            throw new NotAssociatedException(getMetaModelLocal().getTableName(), targetTable);
        }
        return new LazyList<T>(subQuery, null,Registry.instance().getMetaModel(targetTable));
    }

    @Override
    public String toString() {
        return "Model: " + getClass().getName() + ", Table: '" + getMetaModelLocal().getTableName() + "', attributes: " + attributes.toString();
    }

    protected static NumericValidationBuilder validateNumericalityOf(String... attributes) {
        return ValidationHelper.addNumericalityValidators(Model.<Model>getDaClass(), attributes);
    }

    protected static ValidationBuilder validateRegexpOf(String attribute, String pattern) {
        return ValidationHelper.addRegexpValidator(Model.<Model>getDaClass(), attribute, pattern);
    }

    protected static ValidationBuilder validateEmailOf(String attribute) {
        return ValidationHelper.addEmailValidator(Model.<Model>getDaClass(), attribute);
    }

    protected static ValidationBuilder validateRange(String attribute, Number min, Number max) {
        return ValidationHelper.addRangevalidator(Model.<Model>getDaClass(), attribute, min, max);
    }

    protected static ValidationBuilder validatePresenceOf(String... attributes) {
        return ValidationHelper.addPresensevalidators(Model.<Model>getDaClass(), attributes);
    }

    /**
     * Converts a named attribute to <code>java.sql.Date</code> if possible.
     * Acts as a validator if cannot make a conversion.
     *
     * @param attributeName name of attribute to convert to <code>java.sql.Date</code>.
     * @param format format for conversion. Refer to {@link java.text.SimpleDateFormat}
     * @return message passing for custom validation message.
     */
    protected static ValidationBuilder convertDate(String attributeName, String format){
        return ValidationHelper.addDateConverter(Model.<Model>getDaClass(), attributeName, format);
    }

    /**
     * Converts a named attribute to <code>java.sql.Timestamp</code> if possible.
     * Acts as a validator if cannot make a conversion.
     *
     * @param attributeName name of attribute to convert to <code>java.sql.Timestamp</code>.
     * @param format format for conversion. Refer to {@link java.text.SimpleDateFormat}
     * @return message passing for custom validation message.
     */
    protected static ValidationBuilder convertTimestamp(String attributeName, String format){
        return ValidationHelper.addTimestampConverter(Model.<Model>getDaClass(), attributeName, format);
    }

    public static boolean belongsTo(Class<? extends Model> targetClass) {
        String targetTable = Registry.instance().getTableName(targetClass);
        MetaModel metaModel = getMetaModel();
        return (null != metaModel.getAssociationForTarget(targetTable, BelongsToAssociation.class) ||
                null != metaModel.getAssociationForTarget(targetTable, Many2ManyAssociation.class));
    }


     public static void addCallbacks(CallbackListener ... listeners){
         for(CallbackListener listener: listeners ){
             Registry.instance().addListener(getDaClass(), listener);
         }
    }

    /**
     * This method performs validations and then returns true if no errors were generated, otherwise returns false.
     *
     * @return true if no errors were generated, otherwise returns false.
     */
    public boolean isValid(){
        validate();
        return !hasErrors();
    }

    public void validate() {
        fireBeforeValidation(this);
        errors = new HashMap<String, String>();
        List<Validator> theValidators = Registry.instance().getValidators((Class<Model>)getClass());
        if(theValidators != null){
            for (Validator validator : theValidators) {
                validator.validate(this);
            }
        }
        fireAfterValidation(this);
    }

    public boolean hasErrors() {
        return errors != null && errors.size() > 0;
    }

    public void addError(String attribute, String error) {
        getMetaModelLocal().checkAttributeOrAssociation(attribute);
        //TODO: what about multiple errors for the same attribute?
        errors.put(attribute, error);
    }

    public Map<String, String> errors() {
        return Collections.unmodifiableMap(errors);
    }

    /**
     * This is a convenience method to create a model instance already initialized with values.
     * Example:
     * <pre>
     * Person p = Person.create("name", "Sam", "last_name", "Margulis", "dob", "2001-01-07");
     * </pre>
     *
     * The first (index 0) and every other element in the array is an attribute name, while the second (index 1) and every
     * other is a corresponding value.
     *
     * This allows for better readability of code. If you just read this aloud, it will become clear.
     *
     * @param namesAndValues names and values. elements at indexes 0, 2, 4, 8... are attribute names, and elements at
     * indexes 1, 3, 5... are values. Element at index 1 is a value for attribute at index 0 and so on.
     * @return newly instantiated model.
     */
    public static <T extends Model> T create(Object ... namesAndValues){

        if(namesAndValues.length %2 != 0) throw new IllegalArgumentException("number of arguments must be even");

        try{

            Model m = getDaClass().newInstance();
            setNamesAndValues(m, namesAndValues);
            return (T) m;
        }
        catch(IllegalArgumentException e){throw e;}
        catch(ClassCastException e){throw new  IllegalArgumentException("All even arguments must be strings");}
        catch (Exception e){throw new InitException(e);}
    }


    /**
     * This is a convenience method to set multiple values to a model.
     * Example:
     * <pre>
     * Person p = ...
     * Person p = p.set("name", "Sam", "last_name", "Margulis", "dob", "2001-01-07");
     * </pre>
     *
     * The first (index 0) and every other element in the array is an attribute name, while the second (index 1) and every
     * other is a corresponding value.
     *
     * This allows for better readability of code. If you just read this aloud, it will become clear.
     *
     * @param namesAndValues names and values. elements at indexes 0, 2, 4, 8... are attribute names, and elements at
     * indexes 1, 3, 5... are values. Element at index 1 is a value for attribute at index 0 and so on.
     * @return newly instantiated model.
     */
    public Model set(Object ... namesAndValues){
        setNamesAndValues(this, namesAndValues);
        return this;
    }

    private static void setNamesAndValues(Model m, Object... namesAndValues) {

        String[] names = new String[namesAndValues.length / 2];
        Object[] values = new Object[namesAndValues.length / 2];
        int j = 0;
        for (int i = 0; i < namesAndValues.length - 1; i += 2, j++) {
            if (namesAndValues[i] == null) throw new IllegalArgumentException("attribute names cannot be nulls");
            names[j] = (String) namesAndValues[i];
            values[j] = namesAndValues[i + 1];
        }
        m.set(names, values);
    }

    /**
     * This is a convenience method to {@link #create(Object...)}. It will create a new model and will save it
     * to DB. It has the same semantics as {@link #saveIt()}.
     *
     * @param namesAndValues names and values. elements at indexes 0, 2, 4, 8... are attribute names, and elements at
     * indexes 1, 3, 5... are values. Element at index 1 is a value for attribute at index 0 and so on.
     * @return newly instantiated model which also has been saved to DB.     
     */
    public static <T extends Model> T createIt(Object ... namesAndValues){
        T m = (T)create(namesAndValues);
        m.saveIt();
        return m;
    }

    public static <T extends Model> T findById(Object id) {
        if(id == null) return null;

        MetaModel mm = getMetaModel();
        LazyList<T> l = new LazyList<T>(mm.getIdName() + " = ?", new Object[]{id}, mm).limit(1);
        return l.size() > 0 ? l.get(0) : null;

    }

    /**
     * Finder method for DB queries based on table represented by this model. Usually the SQL starts with:
     *
     * <code>"select * from table_name where " + subquery</code> where table_name is a table represented by this model.
     *
     * Code example:
     * <pre>
     *
     * List<Person> teenagers = Person.where("age &gt ? and age &lt ?", 12, 20);
     * // iterate...
     *
     * //same can be achieved (since parameters are optional):
     * List<Person> teenagers = Person.where("age &gt 12 and age &lt 20");
     * //iterate
     * </pre>
     *
     * Limit, offset and order by can be chained like this:
     *
     * <pre>
     * List<Person> teenagers = Person.where("age &gt ? and age &lt ?", 12, 20).offset(101).limit(20).orderBy(age);
     * //iterate
     * </pre>
     *
     * This is a great way to build paged applications.
     *
     *
     * @param subquery this is a set of conditions that normally follow the "where" clause. Example:
     * <code>"department = ? and dob > ?"</code>. If this value is "*" and no parameters provided, then {@link #findAll()} is executed.
     * @param params list of parameters corresponding to the place holders in the subquery.
     * @return instance of <code>LazyList<Model></code> containing results.
     */
    public static <T extends Model> LazyList<T> where(String subquery, Object... params) {
        return find(subquery, params);
    }


    /**
     * This is a convenience method. It will convert a params aray into a string: "param1, param2, etc".
     * This is to expect that a query will have only one place holder like this:
     * <pre>
     * Stirng[] ids = {"1", "2", "4"};
     * List<Person> people = Person.where("id in (?)", ids);
     * </pre>
     * @param subquery query with only one placeholder, expecting a comma-separated string list as value.
     * @param params - Stirng[] - string values for the list.
     * @return list of models according to query selection.
     */
    public static <T extends Model> LazyList<T> where(String subquery, String[] params) {
        String parameter = Util.join(params, "', '");
        parameter = "'" + parameter + "'";
        
        return find(subquery.replaceAll("\\?", parameter));
    }

    /**
     * Synonym of {@link #where(String, Object...)}
     *
     * @param subquery this is a set of conditions that normally follow the "where" clause. Example:
     * <code>"department = ? and dob &gt ?"</code>. If this value is "*" and no parameters provided, then {@link #findAll()} is executed.
     * @param params list of parameters corresponding to the place holders in the subquery.
     * @return instance of <code>LazyList<Model></code> containing results.
     */
    public static <T extends Model> LazyList<T> find(String subquery, Object... params) {

        if(subquery.equals("*") && params.length == 0){
            return findAll();
        }

        if(subquery.equals("*") && params.length != 0){
            throw new IllegalArgumentException("cannot provide parameters with query: '*', use findAll() method instead");
        }

        return  new LazyList(subquery, params, getMetaModel());
    }


    /**
     * Synonym of {@link #first(String, Object...)}.
     *
     * @param subQuery selection criteria, example:
     * <pre>
     * Person johnTheTeenager = Person.findFirst("name = ? and age &gt 13 and age &lt 19 order by age", "John")
     * </pre>
     * Sometimes a query might be just a clause like this:
     * <pre>
     * Person oldest = Person.findFirst("order by age desc")
     * </pre>
     * @param params list of parameters if question marks are used as placeholders
     * @return a first result for this condition. May return null if nothing found.
     */
    public static <T extends Model> T findFirst(String subQuery, Object... params) {
        LazyList<T> results = new LazyList<T>(subQuery, params,getMetaModel()).limit(1);
        return  results.size() > 0 ? results.get(0) : null;
    }


    /**
     * Returns a first result for this condition. May return null if nothing found.
     * If last result is needed, then order by some field and call this nethod:
     *
     * Synonym of {@link #findFirst(String, Object...)}.
     * <pre>
     * //first:
     * Person youngestTeenager= Person.first("age &gt 12 and age &lt 20 order by age");
     *
     * //last:
     * Person oldestTeenager= Person.first("age &gt 12 and age &lt 20 order by age desc");
     * </pre>
     *
     *
     * @param subQuery selection criteria, example:
     * <pre>
     * Person johnTheTeenager = Person.first("name = ? and age &lt 13 order by age", "John")
     * </pre>
     * Sometimes a query might be just a clause like this:
     * <pre>
     * Person p = Person.first("order by age desc")
     * </pre>
     * @param params list of parameters if question marks are used as placeholders
     * @return a first result for this condition. May return null if nothing found.
     */
    public static <T extends Model> T first(String subQuery, Object... params) {
        return (T)findFirst(subQuery, params);
    }


    /**
     * This method is for processing really large result sets. Results found by this method are never cached.
     *
     * @param query query text.
     * @param listener this is a call back implementation which will receive instances of models found.
     */
    public static void find(String query, final ModelListener listener) {
        long start = System.currentTimeMillis();
        final MetaModel metaModel = getMetaModel();
        String sql = metaModel.getDialect().selectStar(metaModel.getTableName(), query);
        new DB(metaModel.getDbName()).find(sql, new RowListenerAdapter() {
            public void onNext(Map<String, Object> rowMap) {
                listener.onModel(instance(rowMap, metaModel));
            }
        });
        LogFilter.logQuery(logger, sql, null, start);
    }


    /**
     * Free form query finder. Example:
     * <pre>
     * List<Rule> rules = Rule.findBySQL("select rule.*, goal_identifier from rule, goal where goal.goal_id = rule.goal_id order by goal_identifier asc, rule_type desc");
     * </pre>
     * Ensure that the query returns all columns associated with this model, so that the resulting models could hydrate itself properly.
     * Returned columns that are not part of this model will be ignored, but can be used for caluses like above.
     *
     * @param fullQuery free-form SQL.
     * @param params parameters if query is parametrized.
     * @param <T> - class that extends Model.
     * @return list of models representing result set.
     */
    public static <T extends Model> LazyList<T> findBySQL(String fullQuery, Object... params) {
        return  new LazyList<T>(getMetaModel(), fullQuery, params);
     }

    /**
     * This method returns all records from this table. If you need to get a subset, look for variations of "find()".
     *
     * @return result list
     */
    public static  <T extends Model> LazyList<T>   findAll() {
        return new LazyList(null, new Object[]{}, getMetaModel());
    }

    /**
     * Adds a new child dependency. The dependency model must be either in many to many relationship to this model or
     * this model has to be in the one to many relationship with the added child model.
     * <p/>
     * In case of one to many relationship, this method will immediately set it's ID as a foreign key on the child and
     * will then save the child.
     *
     * <p/>
     * In case many to many relationship, this method will check if the added child already has an ID. If the child does
     * have an ID, then the method will create a link in the join table. If the child does not have an ID, then this method
     * saves the child first, then creates a record in the join table linking this model instance and the child instance.
     *
     * <p/>
     * This method will throw a {@link NotAssociatedException} in case a model that has no relationship is passed.
     *
     * @param child instance of a model that has a relationship to the current model.
     * Either one to many or many to many relationships are accepted.
     */
    public void add(Model child) {

        //TODO: refactor this method
        String childTable = Registry.instance().getTableName(child.getClass());
        MetaModel metaModel = getMetaModelLocal();
        if (getId() != null) {

            if (metaModel.hasAssociation(childTable, OneToManyAssociation.class)) {
                OneToManyAssociation ass = (OneToManyAssociation)metaModel.getAssociationForTarget(childTable, OneToManyAssociation.class);
                String fkName = ass.getFkName();
                child.set(fkName, getId());
                child.saveIt();//this will cause an exception in case validations fail.
            }else if(metaModel.hasAssociation(childTable, Many2ManyAssociation.class)){
                Many2ManyAssociation ass = (Many2ManyAssociation) metaModel.getAssociationForTarget(childTable, Many2ManyAssociation.class);
                String join = ass.getJoin();
                String sourceFkName = ass.getSourceFkName();
                String targetFkName = ass.getTargetFkName();
                if(child.getId() == null)
                    child.saveIt();

                MetaModel joinMM = Registry.instance().getMetaModel(join);
                if(joinMM == null){
                    new DB(metaModel.getDbName()).exec("INSERT INTO " + join + " ( " + sourceFkName + ", " + targetFkName + " ) VALUES ( " + getId()+ ", " + child.getId() + ")");
                }else{
                    //TODO: write a test to cover this case:
                    //this is for Oracle, many 2 many, and all annotations used, including @IdGenerator. In this case,
                    //it is best to delegate generation of insert to a model (sequences, etc.)
                    try{
                        Model joinModel = (Model)joinMM.getModelClass().newInstance();
                        joinModel.set(sourceFkName, getId());
                        joinModel.set(targetFkName, child.getId());
                        joinModel.saveIt();
                    }
                    catch(InstantiationException e){
                        throw new InitException("failed to create a new instance of class: " + joinMM.getClass()
                                + ", are you sure this class has a default constructor?", e); 
                    }
                    catch(IllegalAccessException e){throw new InitException(e);}
                    finally {
                        QueryCache.instance().purgeTableCache(join);
                        QueryCache.instance().purgeTableCache(metaModel.getTableName());
                        QueryCache.instance().purgeTableCache(childTable);
                    }
                }
             }else if(metaModel.hasAssociation(childTable, OneToManyPolymorphicAssociation.class)){

                OneToManyPolymorphicAssociation association = (OneToManyPolymorphicAssociation)
                        metaModel.getAssociationForTarget(childTable, OneToManyPolymorphicAssociation.class);

                child.set("parent_id", getId());
                child.set("parent_type", this.getClass().getName());
                child.saveIt();

            }else
                throw new NotAssociatedException(metaModel.getTableName(), childTable);
        } else {
            throw new IllegalArgumentException("You can only add associated model to an instance that exists in DB. Save this instance first, then you will be able to add dependencies to it.");
        }
    }


    /**
     * Removes associated child from this instance. The child model should be either in belongs to association (including polymorphic) to this model
     * or many to many association.
     *
     * <p/><p/>
     * In case this is a one to many or polymorphic relationship, this method will simply call <code>child.delete()</code> method. This will
     * render the child object frozen.
     *
     * <p/><p/>
     * In case this is a many to many relationship, this method will remove an associated record from the join table, and
     * will do nothing to the child model or record.
     *
     * <p/><p/>
     * This method will throw a {@link NotAssociatedException} in case a model that has no relationship is passed.
     *
     * @param child model representing a "child" as in one to many or many to many association with this model.
     */
    public void remove(Model child){

        if(child == null) throw new IllegalArgumentException("cannot remove what is null");
        
        if(child.frozen() || child.getId() == null) throw new IllegalArgumentException("Cannot remove a child that does " +
                "not exist in DB (either frozen, or ID not set)");

        String childTable = Registry.instance().getTableName(child.getClass());
        MetaModel metaModel = getMetaModelLocal();
        if (getId() != null) {
            if (metaModel.hasAssociation(childTable, OneToManyAssociation.class)
                    || metaModel.hasAssociation(childTable, OneToManyPolymorphicAssociation.class)) {
                child.delete();
            }else if(metaModel.hasAssociation(childTable, Many2ManyAssociation.class)){
                Many2ManyAssociation ass = (Many2ManyAssociation)metaModel.getAssociationForTarget(childTable, Many2ManyAssociation.class);
                String join = ass.getJoin();
                String sourceFkName = ass.getSourceFkName();
                String targetFkName = ass.getTargetFkName();
                new DB(metaModel.getDbName()).exec("DELETE FROM " + join + " WHERE " + sourceFkName + " = ? AND "
                        + targetFkName + " = ?", getId(), child.getId());
            }else
                throw new NotAssociatedException(metaModel.getTableName(), childTable);
        } else {
            throw new IllegalArgumentException("You can only add associated model to an instance that exists in DB. " +
                    "Save this instance first, then you will be able to add dependencies to it.");
        }
    }

    /**
     * This method will not exit silently like {@link #save()}, it instead will throw {@link activejdbc.validation.ValidationException}
     * if validations did not pass.
     *
     * @return  true if the model was saved, false if you set an ID value for the model, but such ID does not exist in DB.
     */
    public boolean saveIt() {
        boolean result = save();
        purgeEdges();
        if(errors.size() > 0){                
            throw new ValidationException(this);
        }
        return result;
    }



    /**
     * Resets all data in this model, including the ID.
     * After this method, this instance is equivalent to an empty, just created instance.
     */
    public void reset() {
        attributes = new HashMap<String, Object>();
    }

    /**
     * Unfreezes this model. After this method it is possible again to call save() and saveIt() methods.
     * This method will erase the value of ID on this instance, while preserving all other attributes' values.
     *
     * If a record was deleted, it is frozen and cannot be saved. After it is thawed, it can be saved again, but it will
     * generate a new insert statement and create a new record in the table with all the same attribute values.
     *
     * <p/><p/>
     * Synonym for {@link #defrost()}.
     */
    public void thaw(){
        attributes.put(getMetaModelLocal().getIdName(), "");//makes it blank
        frozen = false;
    }

    /**
     * Synonym for {@link #thaw()}.
     */
    public void defrost(){
        thaw();
    }

    /**
     * This method will save data from this instance to a corresponding table in the DB.
     * It will generate insert SQL if the model is new, or update if the model exists in the DB.
     * This method will execute all associated validations and if those validations generate errors,
     * these errors are attached to this instance. Errors are available by {#link #errors() } method.
     * The <code>save()</code> method is mostly for web applications, where code like this is written:
     * <pre>
     * if(person.save())
     *      //show page success
     * else{
     *      request.setAttribute("errors", person.errors());
     *      //show errors page, or same page so that user can correct errors.
     *   }
     * </pre>
     *
     * In other words, this method will not throw validation exceptions. However, if there is a problem in the DB, then
     * there can be a runtime exception thrown.
     *
     * @return true if a model was saved and false if values did not pass validations and the record was not saved.
     * False will also be returned if you set an ID value for the model, but such ID does not exist in DB.
     */
    public boolean save() {
        if(frozen) throw new FrozenException(this);

        fireBeforeSave(this);

        validate();
        if (hasErrors()) {
            return false;
        }

        boolean result;
        if (blank(getId())) {
            result =  insert();
        } else {
            result = update();
        }
        fireAfterSave(this);
        return result;
    }

    public static Long count() {
        MetaModel metaModel = getMetaModel();
        String sql = "SELECT COUNT(*) FROM " + metaModel.getTableName();
        Long result;
        if(metaModel.cached()){
         result = (Long)QueryCache.instance().getItem(metaModel.getTableName(), sql, null);
            if(result == null)
            {
                result = new DB(metaModel.getDbName()).count(metaModel.getTableName());
                QueryCache.instance().addItem(metaModel.getTableName(), sql, null, result);
            }
        }else{
            result = new DB(metaModel.getDbName()).count(metaModel.getTableName());
        }
        return result;
    }

    public static Long count(String query, Object... params) {

        MetaModel metaModel = getMetaModel();

        //attention: this SQL is only used for caching, not for real queries.
        String sql = "SELECT COUNT(*) FROM " + metaModel.getTableName() + " where " + query;

        Long result;
        if(metaModel.cached()){
            result = (Long)QueryCache.instance().getItem(metaModel.getTableName(), sql, params);
            if(result == null){
                result = new DB(metaModel.getDbName()).count(metaModel.getTableName(), query, params);
                QueryCache.instance().addItem(metaModel.getTableName(), sql, params, result);
            }
        }else{
            result = new DB(metaModel.getDbName()).count(metaModel.getTableName(), query, params);
        }
        return result;
    }

    private boolean insert() {

        fireBeforeCreate(this);
        doCreatedAt();
        doUpdatedAt();

        //TODO: need to invoke checkAttributes here too, and maybe rely on MetaModel for this.

        List<String> attrs = metaModelLocal.getAttributeNamesSkip("record_version", metaModelLocal.getIdName());

        List<Object> values = new ArrayList<Object>();
        for (String attribute : attrs) {
            values.add(get(attribute));
        }
        String query = metaModelLocal.getDialect().createParametrizedInsert(metaModelLocal);
        try {
            long id = new DB(metaModelLocal.getDbName()).execInsert(query, metaModelLocal.getIdName(), values.toArray());
            if(metaModelLocal.cached()){
                QueryCache.instance().purgeTableCache(metaModelLocal.getTableName());
            }
            String idName = metaModelLocal.getIdName();
            if(attributes.containsKey(metaModelLocal.getIdNameLower())){
                attributes.put(idName.toLowerCase(), id);
            }else if(attributes.containsKey(metaModelLocal.getIdNameUpper())){
                attributes.put(idName.toUpperCase(), id);
            } else {
                attributes.put(idName, id);
            }
            fireAfterCreate(this);
            return true;
        } catch (Exception e) {
            throw new DBException(e.getMessage(), e);
        }
    }

    private void doCreatedAt() {
        if(getMetaModelLocal().hasAttribute("created_at")){
            //clean just in case.
            attributes.remove("created_at");
            attributes.remove("CREATED_AT");
            attributes.put("created_at", new Timestamp(System.currentTimeMillis()));
        }
    }

    private void doUpdatedAt() {
        if(getMetaModelLocal().hasAttribute("updated_at")){
            //clean just in case.
            attributes.remove("updated_at");
            attributes.remove("UPDATED_AT");
            set("updated_at", new Timestamp(System.currentTimeMillis()));
        }
    }

    private boolean update() {

        doUpdatedAt();

        MetaModel metaModel = getMetaModelLocal();
        String query = "UPDATE " + metaModel.getTableName() + " SET ";
        List<String> names = metaModel.getAttributeNamesSkipGenerated();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            query += name + "= ?";
            if (i < names.size() - 1) {
                query += ", ";
            }
        }

        List values = getAttributeValuesSkipGenerated();

        if(metaModel.hasAttribute("updated_at")){
            query += ", updated_at = ? ";
            values.add(get("updated_at"));
        }

        if(metaModel.isVersioned()){
            query += ", record_version = ? ";
            values.add(getLong("record_version") + 1);
        }
        query += " where " + metaModel.getIdName() + " = ?";
        query += metaModel.isVersioned()? " and record_version = ?" :"";
        values.add(getId());
        if(metaModel.isVersioned()){
            values.add((get("record_version")));
        }
        int updated = new DB(metaModel.getDbName()).exec(query, values.toArray());
        if(metaModel.isVersioned() && updated == 0){
            throw new StaleModelException("Failed to update record for model '" + getClass() +
                    "', with " + getIdName() + " = " + getId() + " and record_version = " + get("record_version") +
                    ". Either this record does not exist anymore, or has been updated to have another record_version.");
        }else if(metaModel.isVersioned()){
            set("record_version", getLong("record_version") + 1);
        }
        if(metaModel.cached()){
            QueryCache.instance().purgeTableCache(metaModel.getTableName());
        }
        return updated > 0;
    }

    private List getAttributeValuesSkipGenerated() {
        List<String> names = getMetaModelLocal().getAttributeNamesSkipGenerated();
        List values = new ArrayList();
        for (String name : names) {
            values.add(get(name));
        }
        return values;
    }


    protected void setMetaModel(MetaModel metaModel){
        this.metaModelLocal = metaModel;
    }

    static <T extends Model> T instance(Map m, MetaModel metaModel) {
        try {
            T instance = (T) metaModel.getModelClass().newInstance();
            instance.setMetaModel(metaModel);
            instance.hydrate(m);
            return instance;
        }
        catch(InstantiationException e){
            throw new InitException("Failed to create a new instance of: " + metaModel.getModelClass() + ", are you sure this class has a default constructor?");
        }
        catch(DBException e){
            throw e;
        }
        catch(InitException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static <T extends Model> Class<T> getDaClass() {
        try {            
            MetaModel mm = Registry.instance().getMetaModelByClassName(getClassName());
            return mm == null? (Class<T>) Class.forName(getClassName()) : mm.getModelClass();
        } catch (Exception e) {
            throw new DBException(e.getMessage(), e);
        }
    }

    private static String getClassName() {
        return new ClassGetter().getClassName();
    }

    public static String getTableName() {        
        return Registry.instance().getTableName(getDaClass());
    }

    public Object getId() {
        return get(getMetaModelLocal().getIdName());
    }

    public String getIdName() {
        return getMetaModelLocal().getIdName();
    }

    private Map<Class, List<Model>> cachedChildren = new HashMap<Class, List<Model>>();

    public void setChildren(Class childClass, List<Model> children) {
        cachedChildren.put(childClass, children);
    }


    static class ClassGetter extends SecurityManager {
        public String getClassName() {
            Class[] classes = getClassContext();
            for (int i = 0; i < classes.length; i++) {
                Class aClass = classes[i];
                if (aClass.getSuperclass().equals(Model.class)) {  // TODO: revisit for inheritance implementation
                    return aClass.getName();
                }
            }
            throw new RuntimeException("failed to determine Model class name, are you sure models have been instrumented?");
        }
    }


    /**
     * Generates INSERT SQL based on this model. Uses single quotes for all string values.
     * Example:
     * <pre>
     *
     * String insert = u.toInsert();
     * //yields this output:
     * //INSERT INTO users (id, first_name, email, last_name) VALUES (1, 'Marilyn', 'mmonroe@yahoo.com', 'Monroe');
     * </pre>
     *
     * @return INSERT SQL based on this model.
     */
    public String toInsert(){
        return toInsert("'", "'");
    }


    /**
     * Generates INSERT SQL based on this model.
     * For instance, for Oracle, the left quote is: "q'{" and the right quote is: "}'".
     * The output will also use single quotes for <code>java.sql.Timestamp</code> and <code>java.sql.Date</code> types.
     *
     * Example:
     * <pre>
     * String insert = u.toInsert("q'{", "}'");
     * //yields this output
     * //INSERT INTO users (id, first_name, email, last_name) VALUES (1, q'{Marilyn}', q'{mmonroe@yahoo.com}', q'{Monroe}');
     * </pre>
     * @param leftStringQuote - left quote for a string value, this can be different for different databases.
     * @param rightStringQuote - left quote for a string value, this can be different for different databases.
     * @return SQL INSERT string;
     */
    public String toInsert(String leftStringQuote, String rightStringQuote){
        return toInsert(new SimpleFormatter(java.sql.Date.class, "'", "'"),
                        new SimpleFormatter(Timestamp.class, "'", "'"),
                        new SimpleFormatter(String.class, leftStringQuote, rightStringQuote));
    }

    /**
     * TODO: write good JavaDoc, use code  inside method above
     *
     * @param formatters
     * @return
     */
    public String toInsert(Formatter ... formatters){
        HashMap<Class, Formatter> formatterMap = new HashMap<Class, Formatter>();

        for(Formatter f: formatters){
            formatterMap.put(f.getValueClass(), f);
        }

        List<String> names = new ArrayList<String>(attributes.keySet());
        Collections.sort(names);
        List<Object> values = new ArrayList();

        for(String name: names){
            Object value = get(name);
            if(value == null){
                values.add("NULL");
            }
            else if (value instanceof String && !formatterMap.containsKey(String.class)){
                values.add("'" + value + "'");
            }else{
                if(formatterMap.containsKey(value.getClass())){
                    values.add(formatterMap.get(value.getClass()).format(value));
                }else{
                    values.add(value);
                }
            }
        }
        return new StringBuffer("INSERT INTO ").append(getMetaModelLocal().getTableName()).append(" (")
                .append(Util.join(names, ", ")).append(") VALUES (").append(Util.join(values, ", ")).append(")").toString();
    }

    /**
     * Use to force-purge cache associated with this table. If this table is not cached, this method has no side effect. 
     */
    public static void purgeCache(){
        MetaModel mm = getMetaModel();
        if(mm.cached()){
            QueryCache.instance().purgeTableCache(mm.getTableName());
        }
    }


    /**
     * Convenience method: converts ID value to Long and returns it.
     * 
     * @return value of attribute corresponding to <code>getIdName()</code>, converted to Long.
     */
    public Long getLongId() {
        Object id = get(getIdName());
        if (id == null) {
            throw new NullPointerException(getIdName() + " is null, cannot convert to Long");
        }
        return Converter.toLong(id);
    }

    private static void purgeEdges(){
        //this is to eliminate side effects of cache on associations.
        //TODO: Need to write tests for cases;
        // 1. One to many relationship. Parent and child are cached.
        //      When a new child inserted, the parent.getAll(Child.class) should see that
        // 2. Many to many. When a new join inserted, updated or deleted, the one.getAll(Other.class) should see the difference.

        //Purge associated targets
        MetaModel metaModel = getMetaModel();
        List<Association> associations = metaModel.getOneToManyAssociations();
        for(Association association: associations){
            QueryCache.instance().purgeTableCache(association.getTarget());
        }

        //Purge edges in case this model represents a join
        List<String> edges = Registry.instance().getEdges(metaModel.getTableName());
        for(String edge: edges){
            QueryCache.instance().purgeTableCache(edge);
        }
    }
}
