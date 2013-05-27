package com.wordnik.swagger.converter

import com.wordnik.swagger.model._
import com.wordnik.swagger.core.SwaggerSpec
import com.wordnik.swagger.core.util.TypeUtil
import com.wordnik.swagger.annotations.ApiProperty

import com.fasterxml.jackson.annotation.{JsonIgnore, JsonProperty}

import org.slf4j.LoggerFactory

import sun.reflect.generics.reflectiveObjects.{ ParameterizedTypeImpl, TypeVariableImpl }

import java.lang.reflect.{ Type, TypeVariable, Field, Modifier, Method, ParameterizedType }
import java.lang.annotation.Annotation
import javax.xml.bind.annotation._

import scala.collection.mutable.{ LinkedHashMap, ListBuffer, HashSet, HashMap }

class ModelPropertyParser(cls: Class[_]) (implicit properties: LinkedHashMap[String, ModelProperty]) {
  private val LOGGER = LoggerFactory.getLogger(classOf[ModelPropertyParser])

	val processedFields = new ListBuffer[String]
	val excludedFieldTypes = new HashSet[String]
  final val positiveInfinity = "Infinity"
  final val negativeInfinity = "-Infinity"

	def parse = Option(cls).map(parseRecursive(_))

  def parseRecursive(hostClass: Class[_]): Unit = {
    for (method <- hostClass.getDeclaredMethods) {
      if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
        parseMethod(method)
    }
    for (field <- hostClass.getDeclaredFields) {
      if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()))
        parseField(field)
    }
    Option(hostClass.getSuperclass).map(parseRecursive(_))
	}

  def parseField(field: Field) = {
    parsePropertyAnnotations(field.getName, field.getAnnotations, field.getGenericType, field.getType)
  }

  def parseMethod(method: Method) = {
    if (method.getParameterTypes == null || method.getParameterTypes.length == 0) {
      parsePropertyAnnotations(method.getName, method.getAnnotations, method.getGenericReturnType, method.getReturnType)
    }
  }

  def extractGetterProperty(methodFieldName: String): (String, Boolean) = {
    if (methodFieldName != null &&
      (methodFieldName.startsWith("get")) &&
      methodFieldName.length > 3) {
      (methodFieldName.substring(3, 4).toLowerCase() + methodFieldName.substring(4, methodFieldName.length()), true)
    } else if (methodFieldName != null &&
      (methodFieldName.startsWith("is")) &&
      methodFieldName.length > 2) {
      (methodFieldName.substring(2, 3).toLowerCase() + methodFieldName.substring(3, methodFieldName.length()), true)
    } else {
      (methodFieldName, false)
    }
  }

  def parsePropertyAnnotations(methodFieldName: String, methodAnnotations: Array[Annotation], genericReturnType: Type, returnType: Type): Any = {
    val e = extractGetterProperty(methodFieldName)
    var name = e._1
    var isGetter = e._2

    var isFieldExists = false
    // var 
    var isJsonProperty = false
    var hasAccessorNoneAnnotation = false
    var methodAnnoOutput = processAnnotations(name, methodAnnotations)
    var required = methodAnnoOutput("required").asInstanceOf[Boolean]
    var position = methodAnnoOutput("position").asInstanceOf[Int]

    var description = {
      if(methodAnnoOutput.contains("description") && methodAnnoOutput("description") != null)
        Some(methodAnnoOutput("description").asInstanceOf[String])
      else None
    }
    var isTransient = methodAnnoOutput("isTransient").asInstanceOf[Boolean]
    var isXmlElement = methodAnnoOutput("isXmlElement").asInstanceOf[Boolean]
    val isDocumented = methodAnnoOutput("isDocumented").asInstanceOf[Boolean]
    var allowableValues = methodAnnoOutput("allowableValues").asInstanceOf[Option[AllowableValues]]

    try {
      val propertyAnnotations = getDeclaredField(this.cls, name).getAnnotations()
      var propAnnoOutput = processAnnotations(name, propertyAnnotations)
      var propPosition = propAnnoOutput("position").asInstanceOf[Int]

      if(allowableValues == None) 
        allowableValues = propAnnoOutput("allowableValues").asInstanceOf[Option[AllowableValues]]
      if(description == None && propAnnoOutput.contains("description") && propAnnoOutput("description") != null) 
        description = Some(propAnnoOutput("description").asInstanceOf[String])
      if(propPosition != 0) position = propAnnoOutput("position").asInstanceOf[Int]
      if(required == false) required = propAnnoOutput("required").asInstanceOf[Boolean]
      isFieldExists = true
      if (!isTransient) isTransient = propAnnoOutput("isTransient").asInstanceOf[Boolean]
      if (!isXmlElement) isXmlElement = propAnnoOutput("isXmlElement").asInstanceOf[Boolean]
      isJsonProperty = propAnnoOutput("isJsonProperty").asInstanceOf[Boolean]
    } catch {
      //this means there is no field declared to look for field level annotations.
      case e: java.lang.NoSuchFieldException => isTransient = false
    }

    //if class has accessor none annotation, the method/field should have explicit xml element annotations, if not
    // consider it as transient
    if (!isXmlElement && hasAccessorNoneAnnotation) {
      isTransient = true
    }

    if (!(isTransient && !isXmlElement && !isJsonProperty) && name != null && (isFieldExists || isGetter || isDocumented)) {
      var paramType = getDataType(genericReturnType, returnType, false)
      var simpleName = getDataType(genericReturnType, returnType, true)

      if (!"void".equals(paramType) && null != paramType && !processedFields.contains(name)) {
        if(!excludedFieldTypes.contains(paramType)) {
          val items = {
            val ComplexTypeMatcher = "([a-zA-Z]*)\\[([a-zA-Z\\.\\-]*)\\].*".r
            paramType match {
              case ComplexTypeMatcher(containerType, basePart) => {
                paramType = containerType
                val ComplexTypeMatcher(t, simpleTypeRef) = simpleName
                val typeRef = {
                  if(simpleTypeRef.indexOf(",") > 0) // it's a map, use the value only
                    simpleTypeRef.split(",").last
                  else simpleTypeRef
                }
                simpleName = containerType
                if(isComplex(simpleTypeRef)) {
                  Some(ModelRef(null, Some(simpleTypeRef), Some(basePart)))
                }
                else Some(ModelRef(simpleTypeRef, None, Some(basePart)))
              }
              case _ => None
            }
          }
          val param = ModelProperty(
					  validateDatatype(simpleName),
            paramType,
            position,
					  required,
					  description,
					  allowableValues.getOrElse(AnyAllowableValues),
					  items)
          properties += name -> param
        }
      }
      processedFields += name
    }
  }

  def validateDatatype(datatype: String): String = {
    if(SwaggerSpec.baseTypes.contains(datatype.toLowerCase))
      datatype.toLowerCase
    else datatype
  }

  def isComplex(typeName: String): Boolean = {
    !SwaggerSpec.baseTypes.contains(typeName.toLowerCase)
  }

	def processAnnotations(name: String, annotations: Array[Annotation]): HashMap[String, Any] = {
    var isTransient = false
    var isXmlElement = false
    var isDocumented = false
    var isJsonProperty = false

    var classname = name
    var updatedName = name
    var required = false
    var defaultValue: String = null
    var description: String = null
    var notes: String = null
    var paramType: String = null
    var allowableValues: Option[AllowableValues] = None
    var paramAccess: String = null
    var wrapperName: String = null
    var position = 0

    for (ma <- annotations) {
      ma match {
        case e: XmlTransient => isTransient = true
        case e: ApiProperty => {
          description = readString(e.value)
          notes = readString(e.notes)
          paramType = readString(e.dataType)
          if(e.required) required = true
          if(e.position != 0) position = e.position
          isDocumented = true
          allowableValues = Some(toAllowableValues(e.allowableValues))
          paramAccess = readString(e.access)
        }
        case e: XmlAttribute => {
          updatedName = readString(e.name, name, "##default")
          updatedName = readString(name, name)
          if(e.required) required = true
          isXmlElement = true
        }
        case e: XmlElement => {
          updatedName = readString(e.name, name, "##default")
          updatedName = readString(name, name)
          defaultValue = readString(e.defaultValue, defaultValue, "\u0000")

          required = e.required
          val xmlElementTypeMethod = classOf[XmlElement].getDeclaredMethod("type")
          val typeValueObj = xmlElementTypeMethod.invoke(e)
          val typeValue = {
          	if (typeValueObj == null) null
            else typeValueObj.asInstanceOf[Class[_]]
          }
          isXmlElement = true
        }
        case e: XmlElementWrapper => wrapperName = readString(e.name, wrapperName, "##default")
        case e: JsonIgnore => isTransient = true
        case e: JsonProperty => {
          updatedName = readString(e.value, name)
          isJsonProperty = true
        }
        case _ => 
      }
    }
    val output = new HashMap[String, Any]
    output += "isTransient" -> isTransient
    output += "isXmlElement" -> isXmlElement
    output += "isDocumented" -> isDocumented
    output += "isJsonProperty" -> isJsonProperty
    output += "name" -> updatedName
    output += "required" -> required
    output += "defaultValue" -> defaultValue
    output += "description" -> description
    output += "notes" -> notes
    output += "paramType" -> paramType
    output += "allowableValues" -> allowableValues
    output += "paramAccess" -> paramAccess
    output += "position" -> position
    output
  }

  def readString(s: String, existingValue: String = null, ignoreValue: String = null): String = {
    if (existingValue != null && existingValue.trim.length > 0) existingValue
    else if (s == null) null
    else if (s.trim.length == 0) null
    else if (ignoreValue != null && s.equals(ignoreValue)) null
    else s.trim
  }

	def getDeclaredField(inputClass: Class[_], fieldName: String): Field = {
    try {
      inputClass.getDeclaredField(fieldName)
    } catch {
      case t: NoSuchFieldException => {
        if (inputClass.getSuperclass != null && inputClass.getSuperclass.getName != "Object") {
          getDeclaredField(inputClass.getSuperclass, fieldName)
        } else {
          throw t
        }
      }
    }
  }

  def toAllowableValues(csvString: String, paramType: String = null): AllowableValues = {
    if (csvString.toLowerCase.startsWith("range[")) {
      val ranges = csvString.substring(6, csvString.length() - 1).split(",")
      toAllowableRange(ranges, csvString)
    } else if (csvString.toLowerCase.startsWith("rangeexclusive[")) {
      val ranges = csvString.substring(15, csvString.length() - 1).split(",")
      toAllowableRange(ranges, csvString)
    } else {
      if (csvString == null || csvString.length == 0) {
        AnyAllowableValues
      } else {
        val params = csvString.split(",").toList
        AllowableListValues(params)
      }
    }
  }

  def toAllowableRange(ranges: Array[String], inputStr: String): AllowableValues = {
    if (ranges.size < 2) {
      LOGGER.error("invalid range input")
      AnyAllowableValues
    }
    else {
      val min = ranges(0) match {
        case e: String if(e == positiveInfinity) => Float.PositiveInfinity
        case e: String if(e == negativeInfinity) => Float.NegativeInfinity
        case e: String => e.toFloat
      }
      val max = ranges(1) match {
        case e: String if(e == positiveInfinity) => Float.PositiveInfinity
        case e: String if(e == negativeInfinity) => Float.NegativeInfinity
        case e: String => e.toFloat
      }
      AllowableRangeValues(min.toString, max.toString)
    }
  }

  def getDataType(genericReturnType: Type, returnType: Type, isSimple: Boolean = false): String = {
    if (TypeUtil.isParameterizedList(genericReturnType)) {
      val parameterizedType = genericReturnType.asInstanceOf[java.lang.reflect.ParameterizedType]
      val valueType = parameterizedType.getActualTypeArguments.head
      "List[" + getDataType(valueType, valueType, isSimple) + "]"
    } else if (TypeUtil.isParameterizedSet(genericReturnType)) {
      val parameterizedType = genericReturnType.asInstanceOf[java.lang.reflect.ParameterizedType]
      val valueType = parameterizedType.getActualTypeArguments.head
      "Set[" + getDataType(valueType, valueType, isSimple) + "]"
    } else if (TypeUtil.isParameterizedMap(genericReturnType)) {
      val parameterizedType = genericReturnType.asInstanceOf[java.lang.reflect.ParameterizedType]
      val typeArgs = parameterizedType.getActualTypeArguments
      val keyType = typeArgs(0)
      val valueType = typeArgs(1)

      val keyName: String = getDataType(keyType, keyType, isSimple)
      val valueName: String = getDataType(valueType, valueType, isSimple)
      "Map[" + keyName + "," + valueName + "]"
    } else if (!returnType.getClass.isAssignableFrom(classOf[ParameterizedTypeImpl]) && returnType.isInstanceOf[Class[_]] && returnType.asInstanceOf[Class[_]].isArray) {
      var arrayClass = returnType.asInstanceOf[Class[_]].getComponentType
      "Array[" + arrayClass.getName + "]"
    } else {
      if (genericReturnType.getClass.isAssignableFrom(classOf[TypeVariableImpl[_]])) {
        genericReturnType.asInstanceOf[TypeVariableImpl[_]].getName
      }
      else if (!genericReturnType.getClass.isAssignableFrom(classOf[ParameterizedTypeImpl])) {
        readName(genericReturnType.asInstanceOf[Class[_]], isSimple)
      } else {
        val parameterizedType = genericReturnType.asInstanceOf[java.lang.reflect.ParameterizedType]
        if (parameterizedType.getRawType == classOf[Option[_]]) {
          val valueType = parameterizedType.getActualTypeArguments.head
          getDataType(valueType, valueType, isSimple)
        }
        else {
          genericReturnType.toString match {
            case "java.lang.Class<?>" => null
            case e: String => e
          }
        }
      }
    }
  }

  def readName(hostClass: Class[_], isSimple: Boolean = true): String = {
    val xmlRootElement = hostClass.getAnnotation(classOf[XmlRootElement])
    val xmlEnum = hostClass.getAnnotation(classOf[XmlEnum])

    val name = {
      if (xmlEnum != null && xmlEnum.value() != null) {
        if (isSimple) readName(xmlEnum.value())
        else hostClass.getName
      } else if (xmlRootElement != null) {
        if ("##default".equals(xmlRootElement.name())) {
          if (isSimple) hostClass.getSimpleName 
          else hostClass.getName
        } else {
          if (isSimple) readString(xmlRootElement.name())
          else hostClass.getName
        }
      } else if (hostClass.getName.startsWith("java.lang.") && isSimple) {
        hostClass.getName.substring("java.lang.".length)
      } else {
        if (isSimple) hostClass.getSimpleName
        else hostClass.getName
      }
    }
    validateDatatype(name)
  }
}