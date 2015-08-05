/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2012-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.features.jmxconfiggenerator.jmxconfig;

import com.google.common.collect.Collections2;
import org.opennms.features.jmxconfiggenerator.jmxconfig.query.FilterCriteria;
import org.opennms.features.jmxconfiggenerator.jmxconfig.query.MBeanServerQuery;
import org.opennms.features.jmxconfiggenerator.jmxconfig.query.MBeanServerQueryException;
import org.opennms.features.jmxconfiggenerator.jmxconfig.query.QueryResult;
import org.opennms.features.jmxconfiggenerator.log.LogAdapter;
import org.opennms.features.namecutter.NameCutter;
import org.opennms.xmlns.xsd.config.jmx_datacollection.Attrib;
import org.opennms.xmlns.xsd.config.jmx_datacollection.CompAttrib;
import org.opennms.xmlns.xsd.config.jmx_datacollection.CompMember;
import org.opennms.xmlns.xsd.config.jmx_datacollection.JmxCollection;
import org.opennms.xmlns.xsd.config.jmx_datacollection.JmxDatacollectionConfig;
import org.opennms.xmlns.xsd.config.jmx_datacollection.Mbean;
import org.opennms.xmlns.xsd.config.jmx_datacollection.Mbeans;
import org.opennms.xmlns.xsd.config.jmx_datacollection.Rrd;

import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.xml.bind.JAXB;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Simon Walter <simon.walter@hp-factory.de>
 * @author Markus Neumann <markus@opennms.com>
 */
public class JmxDatacollectionConfiggenerator {

    private final LogAdapter logger;

    private final List<String> standardVmBeans;

    private final List<String> numbers;

    private final List<String> rras;

    protected final Map<String, Integer> aliasMap = new HashMap<>();

    protected final List<String> aliasList = new ArrayList<>();

    private final Rrd rrd;

    private final NameCutter nameCutter = new NameCutter();

    public JmxDatacollectionConfiggenerator(LogAdapter logger) {
        this.logger = logger;

        // Domains directly from JVMs
        standardVmBeans = new ArrayList<>();
        standardVmBeans.add("JMImplementation");
        standardVmBeans.add("com.sun.management");
        standardVmBeans.add("java.lang");
        standardVmBeans.add("java.nio");
        standardVmBeans.add("java.util.logging");

        // valid number types
        numbers = new ArrayList<>();
        numbers.add("int");
        numbers.add("long");
        numbers.add("double");
        numbers.add("float");
        numbers.add("java.lang.Long");
        numbers.add("java.lang.Integer");
        numbers.add("java.lang.Double");
        numbers.add("java.lang.Float");

        // rrd setup
        rrd = new Rrd();
        rras = new ArrayList<>();
        rrd.setStep(300);
        rras.add("RRA:AVERAGE:0.5:1:2016");
        rras.add("RRA:AVERAGE:0.5:12:1488");
        rras.add("RRA:AVERAGE:0.5:288:366");
        rras.add("RRA:MAX:0.5:288:366");
        rras.add("RRA:MIN:0.5:288:366");
        rrd.getRra().addAll(rras);
    }

    /**
     * This method is for backwards compatibility and allows to not set any ids.
     * If so, ids is set to "*:*".
     *
     * @param mBeanServerConnection
     * @param serviceName
     * @param runStandardVmBeans
     * @param dictionary
     * @return
     * @throws MBeanServerQueryException
     * @throws IOException
     * @throws JMException
     * @deprecated Use {@link #generateJmxConfigModel(List, MBeanServerConnection, String, Boolean, Map)} instead.
     */
    @Deprecated
    public JmxDatacollectionConfig generateJmxConfigModel(MBeanServerConnection mBeanServerConnection, String serviceName, Boolean runStandardVmBeans, Map<String, String> dictionary) throws MBeanServerQueryException, IOException, JMException {
        List<String> ids = new ArrayList<>();
        ids.add("*:*");
        return generateJmxConfigModel(ids, mBeanServerConnection, serviceName, runStandardVmBeans, dictionary);
    }

    public JmxDatacollectionConfig generateJmxConfigModel(List<String> ids, MBeanServerConnection mBeanServerConnection, String serviceName, Boolean runStandardVmBeans, Map<String, String> dictionary) throws MBeanServerQueryException, IOException, JMException {
        logger.debug("Startup values: \n serviceName: " + serviceName + "\n runStandardVmBeans: " + runStandardVmBeans + "\n dictionary" + dictionary);
        aliasList.clear();
        aliasMap.clear();
        nameCutter.setDictionary(dictionary);

        final QueryResult queryResult = queryMbeanServer(ids, mBeanServerConnection, runStandardVmBeans);
        final JmxDatacollectionConfig xmlJmxDatacollectionConfig = createJmxDataCollectionConfig(serviceName, rrd);
        final JmxCollection xmlJmxCollection = xmlJmxDatacollectionConfig.getJmxCollection().get(0);

        for (QueryResult.MBeanResult eachMBeanResult : queryResult.getMBeanResults()) {
            final ObjectName objectName = eachMBeanResult.objectName;
            final Mbean xmlMbean = createMbean(objectName);
            final QueryResult.AttributeResult attributeResult = eachMBeanResult.attributeResult;

            for (MBeanAttributeInfo jmxBeanAttributeInfo : attributeResult.attributes) {
                // check for CompositeData
                if ("javax.management.openmbean.CompositeData".equals(jmxBeanAttributeInfo.getType())) {
                    CompAttrib compAttrib = createCompAttrib(mBeanServerConnection, objectName, jmxBeanAttributeInfo);
                    if (compAttrib != null) {
                        xmlMbean.getCompAttrib().add(compAttrib);
                    }
                }
                if (numbers.contains(jmxBeanAttributeInfo.getType())) {
                    Attrib xmlJmxAttribute = createAttr(jmxBeanAttributeInfo);
                    xmlMbean.getAttrib().add(xmlJmxAttribute);
                } else {
                    logger.warn("The type of attribute '{}' is '{}', but only numbers are supported right now. Ignoring.", jmxBeanAttributeInfo.getName(), jmxBeanAttributeInfo.getType());
                }
            }

            if (!xmlMbean.getAttrib().isEmpty()  || !xmlMbean.getCompAttrib().isEmpty()) {
                xmlJmxCollection.getMbeans().getMbean().add(xmlMbean);
            }
        }

        if (xmlJmxCollection.getMbeans().getMbean().size() != queryResult.getMBeanResults().size()) {
            logger.warn("Queried {} MBeans, but only got {} in the result set.", queryResult.getMBeanResults().size(), xmlJmxCollection.getMbeans().getMbean().size());
        }

        return xmlJmxDatacollectionConfig;
    }

    private JmxDatacollectionConfig createJmxDataCollectionConfig(String serviceName, Rrd rrd) {
        final JmxDatacollectionConfig xmlJmxDatacollectionConfig = new JmxDatacollectionConfig();
        final JmxCollection xmlJmxCollection = new JmxCollection();

        xmlJmxCollection.setName("JSR160-" + serviceName);
        xmlJmxCollection.setRrd(rrd);
        xmlJmxDatacollectionConfig.getJmxCollection().add(xmlJmxCollection);
        xmlJmxCollection.setMbeans(new Mbeans());
        return xmlJmxDatacollectionConfig;
    }

    private QueryResult queryMbeanServer(List<String> ids, MBeanServerConnection mBeanServerConnection, boolean runStandardVmBeans) throws MBeanServerQueryException {
        final MBeanServerQuery query = new MBeanServerQuery()
                .withFilters(ids)
                .fetchValues(false) // we do not fetch values to improve collection speed
                .showMBeansWithoutAttributes(false) // we don't need them
                .sort(true); // sorting makes finding attributes easier
        if (!runStandardVmBeans) {
            query.withIgnoresFilter(Collections2.transform(standardVmBeans, input -> input + ":*"));
        }
        final QueryResult result = query.execute(mBeanServerConnection);
        return result;
    }

    /**
     * Verifies if the given mBeanAttributeInfo matches one of the given criteria list.
     *
     * @param criteriaList
     * @param mBeanAttributeInfo
     * @return True if the given mBeanAttributeInfo matches one of the given criteria list.
     */
    protected boolean matches(Collection<FilterCriteria> criteriaList, ObjectName objectName, MBeanAttributeInfo mBeanAttributeInfo) {
        for (FilterCriteria eachCriteria : criteriaList) {
            if (eachCriteria.matches(mBeanAttributeInfo)) {
                return true;
            }
        }
        return false;
    }

    public void writeJmxConfigFile(JmxDatacollectionConfig jmxDatacollectionConfigModel, String outFile) {
        JAXB.marshal(jmxDatacollectionConfigModel, new File(outFile));
    }

    private Mbean createMbean(ObjectName objectName) {
        String typeAndOthers = objectName.getCanonicalName().substring(objectName.getCanonicalName().lastIndexOf("=") + 1);

        final Mbean mbean = new Mbean();
        mbean.setObjectname(objectName.toString());
        mbean.setName(objectName.getDomain() + "." + typeAndOthers);
        return mbean;
    }

    private CompAttrib createCompAttrib(
            MBeanServerConnection jmxServerConnection,
            ObjectName objectName,
            MBeanAttributeInfo jmxMBeanAttributeInfo) throws JMException, IOException {
        Boolean contentAdded = false;

        CompAttrib xmlCompAttrib = new CompAttrib();
        xmlCompAttrib.setName(jmxMBeanAttributeInfo.getName());
        xmlCompAttrib.setType("Composite");
        xmlCompAttrib.setAlias(jmxMBeanAttributeInfo.getName());

        CompositeData compositeData;
        compositeData = (CompositeData) jmxServerConnection.getAttribute(objectName, jmxMBeanAttributeInfo.getName());
        if (compositeData == null) {
            logger.warn("compositeData for jmxObjectInstance.getObjectName: '{}', jmxMBeanAttributeInfo.getName: '{}' not found. Ignoring.", objectName, jmxMBeanAttributeInfo.getName());
        }
        if (compositeData != null) {
            Set<String> keys = compositeData.getCompositeType().keySet();
            for (String key : keys) {
                Object compositeEntry = compositeData.get(key);
                if (numbers.contains(compositeEntry.getClass().getName())) {
                    contentAdded = true;
                    CompMember xmlCompMember = new CompMember();
                    xmlCompMember.setName(key);

                    String alias = nameCutter.trimByDictionary(jmxMBeanAttributeInfo.getName() + capitalize(key));
                    alias = createAndRegisterUniqueAlias(alias);
                    xmlCompMember.setAlias(alias);
                    xmlCompMember.setType("gauge");
                    xmlCompAttrib.getCompMember().add(xmlCompMember);
                } else {
                    logger.warn("The type of composite member '{}/{}' is '{}', but only numbers are supported right now. Ignoring.", jmxMBeanAttributeInfo.getName(), key, compositeEntry.getClass().getName());
                }
            }
        }

        if (contentAdded) {
            return xmlCompAttrib;
        }
        return null;
    }

    private Attrib createAttr(MBeanAttributeInfo jmxMBeanAttributeInfo) {
        Attrib xmlJmxAttribute = new Attrib();
        xmlJmxAttribute.setType("gauge");
        xmlJmxAttribute.setName(jmxMBeanAttributeInfo.getName());
        String alias = nameCutter.trimByDictionary(jmxMBeanAttributeInfo.getName());
        alias = createAndRegisterUniqueAlias(alias);
        xmlJmxAttribute.setAlias(alias);

        return xmlJmxAttribute;
    }

    protected String createAndRegisterUniqueAlias(String originalAlias) {
        String uniqueAlias = originalAlias;
        if (!aliasMap.containsKey(originalAlias)) {
            aliasMap.put(originalAlias, 0);
            uniqueAlias = 0 + uniqueAlias;
        } else {
            aliasMap.put(originalAlias, aliasMap.get(originalAlias) + 1);
            uniqueAlias = aliasMap.get(originalAlias).toString() + originalAlias;
        }
        //find alias crashes caused by cuting down alias length to 19 chars
        final String uniqueAliasTrimmedTo19Chars = nameCutter.trimByCamelCase(uniqueAlias, 19);
        if (aliasList.contains(uniqueAliasTrimmedTo19Chars)) {
            logger.error("ALIAS CRASH AT :" + uniqueAlias + "\t as: " + uniqueAliasTrimmedTo19Chars);
            uniqueAlias = uniqueAlias + "_NAME_CRASH_AS_19_CHAR_VALUE";
        } else {
            uniqueAlias = uniqueAliasTrimmedTo19Chars;
            aliasList.add(uniqueAlias);
        }
        return uniqueAlias;
    }

    private static String capitalize(String input) {
        if (input != null) {
            return Character.toUpperCase(input.charAt(0)) + input.substring(1);
        }
        return input;
    }

}
