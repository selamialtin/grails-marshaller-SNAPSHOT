package org.grails.plugins.marshallers
import grails.converters.JSON
import groovy.util.logging.Log4j

import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler
import org.codehaus.groovy.grails.commons.GrailsApplication
import org.codehaus.groovy.grails.commons.GrailsClassUtils as GCU
import org.codehaus.groovy.grails.commons.GrailsDomainClass
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty
import org.codehaus.groovy.grails.support.proxy.EntityProxyHandler
import org.codehaus.groovy.grails.support.proxy.ProxyHandler
import org.codehaus.groovy.grails.web.converters.ConverterUtil
import org.codehaus.groovy.grails.web.converters.exceptions.ConverterException
import org.codehaus.groovy.grails.web.converters.marshaller.ObjectMarshaller
import org.codehaus.groovy.grails.web.json.JSONWriter
import org.grails.plugins.marshallers.config.MarshallingConfig
import org.grails.plugins.marshallers.config.MarshallingConfigPool
import org.springframework.beans.BeanWrapper
import org.springframework.beans.BeanWrapperImpl
/**
 * 
 * @author dhalupa
 *
 */
@Log4j
class GenericDomainClassJSONMarshaller implements ObjectMarshaller<JSON> {
	private GrailsApplication application
	private ProxyHandler proxyHandler
	private MarshallingConfigPool configPool

	public GenericDomainClassJSONMarshaller(ProxyHandler proxyHandler, GrailsApplication application, MarshallingConfigPool configPool){
		if(log.debugEnabled) log.debug("Registered json domain class marshaller")
		this.proxyHandler=proxyHandler
		this.application=application
		this.configPool = configPool
	}

	@Override
	public boolean supports(Object object) {
		def clazz=proxyHandler.unwrapIfProxy(object).getClass()
		boolean supports = configPool.get(clazz) != null
		if(log.debugEnabled) log.debug("Support for $clazz is $supports")
		return supports
	}

	@Override
	public void marshalObject(Object v,JSON json)	throws ConverterException {
		JSONWriter writer = json.getWriter()
		def value = proxyHandler.unwrapIfProxy(v)
		Class clazz = value.getClass()
		MarshallingConfig mc= configPool.get(clazz, true)
		GrailsDomainClass domainClass = (GrailsDomainClass)application.getArtefact(
				DomainClassArtefactHandler.TYPE, ConverterUtil.trimProxySuffix(clazz.getName()))
		BeanWrapper beanWrapper = new BeanWrapperImpl(value)

		writer.object()
		if(mc.shouldOutputClass){
			writer.key("class").value(clazz.getName())
		}
		if(mc.shouldOutputIdentifier){
			GrailsDomainClassProperty id = domainClass.getIdentifier()
			Object idValue = extractValue(value, id)
			json.property("id", idValue)
		}

		if (mc.shouldOutputVersion) {
			GrailsDomainClassProperty versionProperty = domainClass.getVersion()
			Object version = extractValue(value, versionProperty)
			json.property("version", version)
		}

		GrailsDomainClassProperty[] properties = domainClass.getPersistentProperties()

		for (GrailsDomainClassProperty property : properties) {
			if(!mc.ignore?.contains(property.getName())){
				writer.key(property.getName())
				if(mc.serializer?.containsKey(property.getName())){
					mc.serializer[property.getName()].call(value,writer)
				}
				else{
					if (!property.isAssociation()) {
						// Write non-relation property
						Object val = beanWrapper.getPropertyValue(property.getName())
						json.convertAnother(val)
					}
					else {
						Object referenceObject = beanWrapper.getPropertyValue(property.getName())
						if (mc.deep?.contains(property.getName())) {
							if (referenceObject == null) {
								writer.value(null)
							}
							else {
								referenceObject = proxyHandler.unwrapIfProxy(referenceObject)
								if (referenceObject instanceof SortedMap) {
									referenceObject = new TreeMap((SortedMap) referenceObject)
								}
								else if (referenceObject instanceof SortedSet) {
									referenceObject = new TreeSet((SortedSet) referenceObject)
								}
								else if (referenceObject instanceof Set) {
									referenceObject = new HashSet((Set) referenceObject)
								}
								else if (referenceObject instanceof Map) {
									referenceObject = new HashMap((Map) referenceObject)
								}
								else if (referenceObject instanceof Collection) {
									referenceObject = new ArrayList((Collection) referenceObject)
								}
								json.convertAnother(referenceObject)
							}
						}
						else {
							if (referenceObject == null) {
								json.value(null)
							}
							else {
								GrailsDomainClass referencedDomainClass = property.getReferencedDomainClass()
								// Embedded are now always fully rendered
								if (referencedDomainClass == null || property.isEmbedded() || GCU.isJdk5Enum(property.getType())) {
									json.convertAnother(referenceObject)
								}
								else if (property.isOneToOne() || property.isManyToOne() || property.isEmbedded()) {
									// add listViewTest property
									Object text_field = GCU.getStaticPropertyValue(referencedDomainClass.clazz, "listboxText")
									if (text_field) {
										def ltp = []
										if (text_field instanceof Collection) {
											text_field.each {
												ltp << referencedDomainClass.getPropertyByName(it)
											}
										}
										else {
											ltp << referencedDomainClass.getPropertyByName(text_field)
										}
										asShortObjectWithListText(referenceObject, json, referencedDomainClass.getIdentifier(), ltp, referencedDomainClass)
									} else {
										asShortObject(referenceObject, json, referencedDomainClass.getIdentifier(), referencedDomainClass)
									}
								}
								else {
									GrailsDomainClassProperty referencedIdProperty = referencedDomainClass.getIdentifier()
									@SuppressWarnings("unused")
											String refPropertyName = referencedDomainClass.getPropertyName()
									if (referenceObject instanceof Collection) {
										Collection o = (Collection) referenceObject
										writer.array()
										for (Object el : o) {
											asShortObject(el, json, referencedIdProperty, referencedDomainClass)
										}
										writer.endArray()
									}
									else if (referenceObject instanceof Map) {
										Map<Object, Object> map = (Map<Object, Object>) referenceObject
										for (Map.Entry<Object, Object> entry : map.entrySet()) {
											String key = String.valueOf(entry.getKey())
											Object o = entry.getValue()
											writer.object()
											writer.key(key)
											asShortObject(o, json, referencedIdProperty, referencedDomainClass)
											writer.endObject()
										}
									}
								}
							}
						}
					}
				}
			}
		}

		mc.virtual?.each{prop,callable->
			writer.key(prop)
			mc.virtual[prop].call(value,json)
		}

		writer.endObject()
	}
	
	protected void asShortObject(Object refObj, JSON json, GrailsDomainClassProperty idProperty, GrailsDomainClass referencedDomainClass) throws ConverterException {
		Object idValue
		if (proxyHandler instanceof EntityProxyHandler) {
			idValue = ((EntityProxyHandler) proxyHandler).getProxyIdentifier(refObj)
			if (idValue == null) {
				idValue = extractValue(refObj, idProperty)
			}
		}
		else {
			idValue = extractValue(refObj, idProperty)
		}
		JSONWriter writer = json.getWriter()
		writer.object()
		writer.key("id").value(idValue)
		writer.endObject()
	}
	
	protected void asShortObjectWithListText(Object refObj, JSON json, GrailsDomainClassProperty idProperty, Collection listTextProperties, GrailsDomainClass referencedDomainClass) throws ConverterException {
		Object idValue
		if (proxyHandler instanceof EntityProxyHandler) {
			idValue = ((EntityProxyHandler) proxyHandler).getProxyIdentifier(refObj)
			if (idValue == null) {
				idValue = extractValue(refObj, idProperty)
			}
		}
		else {
			idValue = extractValue(refObj, idProperty)
		}
		JSONWriter writer = json.getWriter()
		writer.object()
		writer.key("id").value(idValue)
		listTextProperties?.each {
			GrailsDomainClassProperty p = (GrailsDomainClassProperty)it
			Object listTextValue = extractValue(refObj, p)
			writer.key(p.name).value(listTextValue)
		}
		
		writer.endObject()
	}

	protected Object extractValue(Object domainObject, GrailsDomainClassProperty property) {
		BeanWrapper beanWrapper = new BeanWrapperImpl(domainObject)
		return beanWrapper.getPropertyValue(property.getName())
	}

	protected boolean isRenderDomainClassRelations() {
		return false
	}
	


}
