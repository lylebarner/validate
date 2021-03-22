// Copyright 2006-2019, by the California Institute of Technology.
// ALL RIGHTS RESERVED. United States Government Sponsorship acknowledged.
// Any commercial use must be negotiated with the Office of Technology Transfer
// at the California Institute of Technology.
//
// This software is subject to U. S. export control laws and regulations
// (22 C.F.R. 120-130 and 15 C.F.R. 730-774). To the extent that the software
// is subject to U.S. export control laws and regulations, the recipient has
// the responsibility to obtain export licenses or other export authority as
// may be required before exporting such information to foreign countries or
// providing access to foreign nationals.
//
// $Id$
package gov.nasa.pds.tools.validate.content.table;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.math.NumberUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.primitives.UnsignedLong;

import gov.nasa.pds.label.object.FieldDescription;
import gov.nasa.pds.label.object.FieldType;
import gov.nasa.pds.label.object.RecordLocation;
import gov.nasa.pds.label.object.TableRecord;
import gov.nasa.pds.objectAccess.DelimitedTableRecord;
import gov.nasa.pds.objectAccess.FixedTableRecord;
import gov.nasa.pds.tools.label.ExceptionType;
import gov.nasa.pds.tools.util.FileService;
import gov.nasa.pds.tools.validate.ProblemListener;
import gov.nasa.pds.tools.validate.ProblemType;
import gov.nasa.pds.tools.validate.rule.pds4.DateTimeValidator;

/**
 * Class that performs content validation on the field values of a given
 *  record.
 * 
 * @author mcayanan
 *
 */
public class FieldValueValidator {
  private static final Logger LOG = LoggerFactory.getLogger(FieldValueValidator.class);
  /** List of invalid values. */
  private final List<String> INF_NAN_VALUES = Arrays.asList(
      "INF", "-INF", "+INF", "NAN", "-NAN", "+NAN");
  
  /** List of valid datetime formats. */
  private static final Map<String, String> DATE_TIME_VALID_FORMATS = 
      new HashMap<String, String>();
  static{
    DATE_TIME_VALID_FORMATS.put(
        FieldType.ASCII_DATE_DOY.getXMLType(), "YYYY[Z], YYYY-DOY[Z]");
    DATE_TIME_VALID_FORMATS.put(
        FieldType.ASCII_DATE_TIME_DOY.getXMLType(), 
        "YYYY[Z], YYYY-DOYThh[Z], YYYY-DOYThh:mm[Z], "
        + "YYYY-DOYThh:mm:ss[.ffffff][Z]");
    DATE_TIME_VALID_FORMATS.put(
        FieldType.ASCII_DATE_TIME_DOY_UTC.getXMLType(), 
        "YYYYZ, YYYY-DOYThhZ, YYYY-DOYThh:mmZ, YYYY-DOYThh:mm:ss[.ffffff]Z");
    DATE_TIME_VALID_FORMATS.put(FieldType.ASCII_DATE_TIME_YMD.getXMLType(), 
        "YYYY[Z], YYYY-MM-DDThh[Z], YYYY-MM-DDThh:mm[Z], "
        + "YYYY-MM-DDThh:mm:ss[.ffffff][Z]");
    DATE_TIME_VALID_FORMATS.put(FieldType.ASCII_DATE_TIME_YMD_UTC.getXMLType(), 
        "YYYYZ, YYYY-MM-DDThhZ, YYYY-MM-DDThh:mmZ, "
        + "YYYY-MM-DDThh:mm:ss[.ffffff]Z");
    DATE_TIME_VALID_FORMATS.put(FieldType.ASCII_DATE_YMD.getXMLType(), 
        "YYYY[Z], YYYY-MM[Z], YYYY-MM-DD[Z]");
    DATE_TIME_VALID_FORMATS.put(FieldType.ASCII_TIME.getXMLType(), 
        "hh:mm:ss[.ffffff][Z]");
  }
  
  /** Container to capture messages. */
  private ProblemListener listener;
  
  private static final Pattern formatPattern = Pattern.compile(
      "%([\\+,-])?([0-9]+)(\\.([0-9]+))?([doxfeEs])");
  private static final Pattern leadingWhiteSpacePattern = Pattern.compile(
      "\\s+.*");
  private static final Pattern trailingWhiteSpacePattern = Pattern.compile(
      ".*\\s+");
  private static final Pattern asciiBibCodePattern = Pattern.compile(
      "\\d{4}[A-Za-z\\d\\.\\&]{5}[A-Za-z\\d\\.]{9}[A-Z]");
  private static final Pattern asciiIntegerPattern = Pattern.compile(
      "[+-]?\\d+");
  private static final Pattern asciiNonNegativeIntPattern = Pattern.compile(
      "[+]?\\d+");
  private static final Pattern asciiReal = Pattern.compile(
      "(\\+|-)?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([Ee](\\+|-)?[0-9]+)?");
  private static final Pattern asciiNumericBase2Pattern = Pattern.compile(
      "[0-1]{1,255}");
  private static final Pattern asciiNumericBase8Pattern = Pattern.compile(
      "[0-7]{1,255}");
  private static final Pattern asciiNumericBase16Pattern = Pattern.compile(
      "[0-9a-fA-F]{1,255}");
  private static final Pattern asciiMd5ChecksumPattern = Pattern.compile(
      "[0-9a-fA-F]{32}");
  private static final Pattern asciiDoiPattern = Pattern.compile(
      "10\\.\\S+/\\S+");
  private static final Pattern asciiLidPattern = Pattern.compile(
      "urn:[a-z]+:[a-z]+:([0-9a-z-._]:?)+");
  private static final Pattern asciiLidVidPattern = Pattern.compile(
      "urn:[a-z]+:[a-z]+:([0-9a-z-._]:?)+::[1-9][0-9]*\\.[0-9]+");
  private static final Pattern asciiLidVidLidPattern = Pattern.compile(
      "urn:[a-z]+:[a-z]+:([0-9a-z-._]:?)+::[1-9][0-9]*\\.[0-9]+");
  private static final Pattern asciiVidPattern = Pattern.compile(
      "[1-9][0-9]*\\.[0-9]+(\\.[0-9]+)?(\\.[0-9]+)?");
  private static final Pattern asciiDirPathNamePattern = Pattern.compile(
      "[A-Za-z0-9][A-Za-z0-9_-]*[A-Za-z0-9]");

  // https://github.com/NASA-PDS/validate/issues/299 Validate tool does not PASS a bundle with a single-character filename
  // A better pattern allows for at least one character file name.
  private static final Pattern asciiFileNamePattern = Pattern.compile(
      "[A-Za-z0-9]*[A-Za-z0-9-_\\.]*[A-Za-z0-9]\\.[A-Za-z0-9]+");
// buggy: Commented out and kept for reference.
// buggy: The below pattern forces the file name to be at least 2 characters.
// buggy: private static final Pattern asciiFileNamePattern = Pattern.compile(
// buggy:     "[A-Za-z0-9][A-Za-z0-9-_\\.]*[A-Za-z0-9]\\.[A-Za-z0-9]+");
  private static final Pattern dirPattern = Pattern.compile(
      "/?([A-Za-z0-9][A-Za-z0-9_-]*[A-Za-z0-9]/?)*");
  
  /**
   * Constructor.
   * 
   * @param target The label.
   * @param dataFile The data file.
   */
  public FieldValueValidator(ProblemListener listener) {
    this.listener = listener;
  }
  
  /**
   * Validates the field values in the given record.
   * 
   * @param record The record containing the fields to validate.
   * @param fields An array of the field descriptions.
   */
  public void validate(TableRecord record, FieldDescription[] fields) throws FieldContentFatalException {
    validate(record, fields, true);
  }
  
  /**
   * Validates the field values in the given record.
   * 
   * @param record The record containing the fields to validate.
   * @param fields An array of the field descriptions.
   * @param checkFieldFormat A flag to determine whether to check the field
   *  values against its specified field format, if present in the label.
   */
  public void validate(TableRecord record, FieldDescription[] fields, boolean checkFieldFormat) throws FieldContentFatalException{
    // Set variable if we get an error that will be a problem for all records
    boolean fatalError = false;

    //LOG.debug("validate:checkFieldFormat,fields.length {},{}",checkFieldFormat,fields.length);
    //LOG.debug("validate:checkFieldFormat,fields {}",fields);
    //LOG.debug("validate:record.getLocation().getRecord() {}",record.getLocation().getRecord());

    int actualFieldNumber = 1;
    for (int i = 0; i < fields.length; i++) {
      String value = "dummy_value";   // Set to a dummy value to allow inspection when the value changed to a legitimate value.
      //LOG.info("validate:i,fields.length {},{}",i,fields.length);
      try {
        //String value = record.getString(i+1);
        value = record.getString(i+1);
        LOG.debug("validate:i,value {},[{}]",i,value);

        //LOG.debug("FieldValueValidator:validate:record.getLocation().getRecord(),value {},[{}]",record.getLocation().getRecord(),value);
        //LOG.debug("FieldValueValidator:validate:i,getClass().getName() {},{}",i,fields[i].getClass().getName());
        // issue_298: validate misses double quotes within a delimited table
        //
        // New logic to check if the field starts with a double quote and then also contain a double quote inside.

        String sanitizedValue = value;
        boolean fieldIsEnclosedByQuotes = false;
        // Remove the leading and trailing quotes from value if the field is enclosed by it.
        if (value.startsWith("\"") && value.endsWith("\"")) {
            fieldIsEnclosedByQuotes = true;
            sanitizedValue = value.substring(1, value.length()-1);
        }

        LOG.debug("fieldIsEnclosedByQuotes,value,sanitizedValue [{}],[{}]",fieldIsEnclosedByQuotes,value,sanitizedValue);

        if (fieldIsEnclosedByQuotes && sanitizedValue.contains("\"")) {
                String message = "The field value '" + value.trim()
                  + "' that starts with double quote should not contain double quote(s)";
                addTableProblem(ExceptionType.ERROR,
                    ProblemType.INVALID_FIELD_VALUE,
                    message,
                    record.getLocation(),
                    (i + 1));
        }

        // issue_209: fix for incorrect field number
        if (i<(fields.length-1) ) {
           if (fields[i+1].getOffset()!=fields[i].getOffset())
        	   actualFieldNumber++;   
        }
        // Adding debug could be time consuming for large files.  Uncommenting should be done by developer only for debugging.
        //LOG.debug("validate:i,value,fields[i] {},{},{}",i,value,fields[i]);
        //LOG.debug("validate:i,fields[i].getLength(),fields[i].getMaxLength() {},{},{}",i,fields[i].getLength(),fields[i].getMaxLength());
        
        // Check that the length of the field value does not exceed the
        // maximum field length, if specified
        if (fields[i].getMaxLength() != -1) {
          if (value.trim().length() > fields[i].getMaxLength()) {
            String message = "The length of the value '" + value.trim()
              + "' exceeds the defined max field length (expected max "
              + fields[i].getMaxLength()
              + ", got " + value.trim().length() + ")"; 
            addTableProblem(ExceptionType.ERROR,
                ProblemType.FIELD_VALUE_TOO_LONG,
                message,
                record.getLocation(),
                (i + 1));
          }        
        }

        // issue_209: when checkFieldFormat=false, it's Table_Binary
        if (checkFieldFormat) { 
        	// issue_56: Validate that Table_Character fields do not overlap based upon field length definitions
            // Better line
            // if (((i+1)<fields.length) && (fields[i].getOffset()+fields[i].getLength() > fields[i+1].getOffset()))
            // The next line is hard to read.  Perhaps the parenthesis should surround the 2nd > comparison.
        	if (((i+1)<fields.length) && (fields[i].getOffset()+fields[i].getLength()) > fields[i+1].getOffset()) {
        		String message = "This field overlaps the next field. Current field ends at " 
        				+ (fields[i].getOffset()+fields[i].getLength()) 
        				+ ". Next field starts at " + fields[i+1].getOffset();
                LOG.error(message);
        		addTableProblem(ExceptionType.ERROR,
        				ProblemType.FIELD_VALUE_OVERLAP,
        				message,
        				record.getLocation(),
        				(i+1));
        	}
        }

        // issue_56: Validate that fields do not overlap based upon field length definitions
    	if ((i+1)<fields.length) {
    	    // If stopBit is set and we aren't at the end of the field, 
    	    // we should check for overlapping bit fields
    	    if (fields[i].getStopBit()>0 && fields[i].getStopBit()!=fields[i].getLength()*8) {
    	    	
    	        // first check if the stop bit is longer than the field length
    	        if (fields[i+1].getStartBit() > 1) {  // only check overlap is next start bit
    	            // Next, if next startBit > -1 we know we have another bit field to check  
        	        // Let's check the bit fields aren't overlapping
        	        if (fields[i].getStopBit() >= fields[i+1].getStartBit()) {
                        String message = "The bit field overlaps the next field. "
                                + "Current stop_bit_location: "
                                + (fields[i].getStopBit()+1) 
                                + ". Next start_bit_location: " + (fields[i+1].getStartBit()+1);
                        addTableProblem(ExceptionType.ERROR,
                                ProblemType.FIELD_VALUE_OVERLAP,
                                message,
                                record.getLocation(),
                                actualFieldNumber);
                              //  (i+1));
                        fatalError = true;
        	        }
    	        }
    	    // Otherwise, we are just reading a normal Field_Character or Field_Binary
        	} else {
        		// issue_209: incorrect error when the current offset and next offset are same
                // Adding debug could be time consuming for large files.  Uncommenting should be done by developer only for debugging.
                //LOG.debug("validate:i,fields[i].getOffset(),fields[i].getLength(),fields[i+1].getOffset() {},{},{},{}",i,fields[i].getOffset(),fields[i].getLength(),fields[i+1].getOffset());
                // issue_257: Product with incorrect table binary definition pass validation
                // Corrected logic: using the OR logic || and put parenthesis surround the 2nd check for readability.
        		if ((fields[i].getOffset()>fields[i+1].getOffset()) || (fields[i].getOffset()+fields[i].getLength()) > fields[i+1].getOffset()) {       
        			String message = "This field overlaps the next field. Current field ends at " 
        					+ (fields[i].getOffset()+fields[i].getLength()+1) 
        					+ ". Next field starts at " + (fields[i+1].getOffset()+1);
                    LOG.error("{}",message);
        			addTableProblem(ExceptionType.ERROR,
        					ProblemType.FIELD_VALUE_OVERLAP,
        					message,
        					record.getLocation(),
        					(i+1));
        			fatalError = true;
        		}
                // Adding debug could be time consuming for large files.  Uncommenting should be done by developer only for debugging.
                //else {
                //    LOG.debug("validate:column valid i {}",i);
                //}
        	}
    	}

        // Per the DSV standard in section 4C.1 of the Standards Reference,
        // empty fields are ok for DelimitedTableRecord and space-padded empty fields are ok for FixedTableRecord
        if (value.isEmpty() || (value.trim().isEmpty() && record instanceof FixedTableRecord)) {
          addTableProblem(ExceptionType.DEBUG, 
                  ProblemType.BLANK_FIELD_VALUE,
                  "Field is blank.", 
                  record.getLocation(), (i+1));
        } else if (!value.trim().isEmpty()) {  // Check that the value of the field matches the defined data type
          try {
            checkType(value.trim(), fields[i].getType());
            addTableProblem(ExceptionType.DEBUG,
                ProblemType.FIELD_VALUE_DATA_TYPE_MATCH,
                "Value '" + value.trim() + "' matches its data type '"
                    + fields[i].getType().getXMLType() + "'.",
                record.getLocation(),
                (i + 1));
          } catch (Exception e) {
            String message = "Value does not match its data type '"
                + fields[i].getType().getXMLType() + "': " + e.getMessage(); 
            addTableProblem(ExceptionType.ERROR,
                ProblemType.FIELD_VALUE_DATA_TYPE_MISMATCH,
                message,
                record.getLocation(),
                (i + 1));
          }
          // Check that the format of the field value in the table matches 
          // the defined formation of the field
          if (checkFieldFormat) {
            // Due to CCB-214, the tool should validate against the 
            // validation_format field for Character Tables.
            if (record instanceof FixedTableRecord &&
                !fields[i].getValidationFormat().isEmpty()) {      
              checkFormat(value, fields[i].getValidationFormat(), i + 1, 
                  record.getLocation());        
            }
            if (record instanceof DelimitedTableRecord &&
                !fields[i].getFieldFormat().isEmpty()) {
              checkFormat(value, fields[i].getFieldFormat(), i + 1,
                  record.getLocation());
            }
          }
          // Check that the field value is within the defined min/max values
          if (fields[i].getMinimum() != null || 
              fields[i].getMaximum() != null) {
            checkMinMax(value.trim(), fields[i].getMinimum(), 
                fields[i].getMaximum(), i + 1, record.getLocation(),
                fields[i].getType());
          } 
        } else {
          try {
        	  
              checkType(value, fields[i].getType());
              addTableProblem(ExceptionType.DEBUG, 
                      ProblemType.BLANK_FIELD_VALUE,
                      "Field is blank.", 
                      record.getLocation(), (i+1));
            } catch (Exception e) {
              String message = "Value does not match its data type '"
                  + fields[i].getType().getXMLType() + "': " + e.getMessage(); 
              addTableProblem(ExceptionType.ERROR,
                  ProblemType.FIELD_VALUE_DATA_TYPE_MISMATCH,
                  message,
                  record.getLocation(),
                  (i + 1));
            }        
        }
      } catch (Exception e) {
        addTableProblem(ExceptionType.ERROR,
            ProblemType.BAD_FIELD_READ,
            "Error while getting field value: " + e.getMessage(),
            record.getLocation(),
            (i + 1));
        fatalError = true;

        // Print the stack trace to an external file for inspection.
        FileService.printStackTraceToFile(null,e);
      }
    }
    
    // Raise exception if we get a fatal error to avoid overflow of error messages
    // for every records
    if (fatalError) {
        LOG.error("Fatal field content read error. Discontinue reading records.  Last read record {}",record.getLocation().getRecord()); 
        throw new FieldContentFatalException("Fatal field content read error. Discontinue reading records."); 
    }
  }

  /**
   * Checks that the given value is within the min/max range.
   * 
   * @param value The field value to validate.
   * @param minimum The minimum value.
   * @param maximum The maximum value.
   * @param recordLocation The record location where the field is located.
   * @param type The field type of the column value to validate.
   */
  private void checkMinMax(String value, Double minimum, Double maximum, 
      int fieldIndex, RecordLocation recordLocation, FieldType type) {
    value = value.trim();

    // https://github.com/NASA-PDS/validate/issues/297 Content validation of ASCII_Integer field does not accept value with leading zeroes
    // Some values may start with a zero but the user may not intend for it to be an Octal so
    // the leading zeros have to be removed:
    //
    //       "000810"  gets corrected to "810"
    //       "-00810"  gets corrected to "-810"
    //
    // Only perform the leading zeros correction for these field types:
    //
    //     FieldType.ASCII_REAL
    //     FieldType.ASCII_INTEGER
    //     FieldType.ASCII_NONNEGATIVE_INTEGER
    //
    boolean issueWithLeadingZerosRemovalFlag = false;
    if ((type == FieldType.ASCII_REAL || type == FieldType.ASCII_INTEGER || type == FieldType.ASCII_NONNEGATIVE_INTEGER) &&
        (value.startsWith("0") || value.startsWith("-0")))  {
        String originalValue = value;
        try {
            // Attempt to convert from string to double, then back to string:  "000810" >> 810.0 >> "810.0"
            value = Double.toString(Double.parseDouble(value));
        } catch (java.lang.NumberFormatException ex) {
            // If there is problem with the value because it contains letters, set issueWithLeadingZerosRemovalFlag to true
            // so we know not to make another attempt with NumberUtils.isCreatable() below. 
            issueWithLeadingZerosRemovalFlag = true;
        }

        LOG.debug("checkMinMax:originalValue,value,minimum,maximum {},{},{},{}",originalValue,value,minimum,maximum);
        System.out.println("checkMinMax:originalValue,value " + originalValue + " " + value);
    }

    LOG.debug("checkMinMax:FIELD_VALUE,FIELD_LENGTH [{}],{}",value,value.length());

    //if (NumberUtils.isCreatable(value)) 
    if (!issueWithLeadingZerosRemovalFlag || NumberUtils.isCreatable(value)) {
      // In comparing double or floats, it is important how these values are built.
      // Since the values of 'minimum' and 'maximum' variables are both of types Double,
      // it may be best to convert the String variable 'value' to Double as well.
      //
      // Note that is OK to use Number number = NumberUtils.createNumber(value) but some precision is lost even
      // when both values 0.12345 (one built with createDouble() and one built with createNumber()) should be identical: 
      //
      //     Field has a value '0.12345' that is greater than the defined maximum value '0.12345'.
      //
      // The below line is commented out and kept for education purpose.
      //
      //Number number = NumberUtils.createNumber(value);

      Double number = NumberUtils.createDouble(value);  // Create a Double value from '0.12345' to match 'mininum' and 'maximum' variables' type.
      if (minimum != null) {
        if (number.doubleValue() < minimum.doubleValue()) {
          String message = "Field has a value '" + value
              + "' that is less than the defined minimum value '"
              + minimum.toString() +  "'. "; 
          addTableProblem(ExceptionType.ERROR,
              ProblemType.FIELD_VALUE_OUT_OF_MIN_MAX_RANGE,
              message,
              recordLocation,
              fieldIndex);
        } else {
          String message = "Field has a value '" + value
              + "' that is greater than the defined minimum value '"
              + minimum.toString() +  "'. "; 
          addTableProblem(ExceptionType.DEBUG,
              ProblemType.FIELD_VALUE_IN_MIN_MAX_RANGE,
              message,
              recordLocation,
              fieldIndex);          
        }
      }
      if (maximum != null) {
        if (number.doubleValue() > maximum.doubleValue()) {
          String message = "Field has a value '" + value
              + "' that is greater than the defined maximum value '"
              + maximum.toString() +  "'. "; 
          addTableProblem(ExceptionType.ERROR,
              ProblemType.FIELD_VALUE_OUT_OF_MIN_MAX_RANGE,
              message,
              recordLocation,
              fieldIndex);
        } else {
          String message = "Field has a value '" + value
              + "' that is less than the defined maximum value '"
              + maximum.toString() +  "'. "; 
          addTableProblem(ExceptionType.DEBUG,
              ProblemType.FIELD_VALUE_IN_MIN_MAX_RANGE,
              message,
              recordLocation,
              fieldIndex);          
        }
      }
    } else {
      // Value cannot be converted to a number
      String message = "Cannot cast field value '" + value
          + "' to a Number data type to validate against the min/max"
          + " values defined in the label.";
      addTableProblem(ExceptionType.ERROR,
          ProblemType.FIELD_VALUE_NOT_A_NUMBER,
          message,
          recordLocation,
          fieldIndex);
    }
  }
  
  /**
   * Checks that the given value matches its defined field type.
   * 
   * @param value The field value to validate.
   * @param type The field type to check against.
   * 
   * @throws Exception If the value was found to be invalid.
   */
  private void checkType(String value, FieldType type) throws Exception {
    //File and directory naming rules are checked in the
    //FileAndDirectoryNamingRule class
    if (INF_NAN_VALUES.contains(value)) {
      throw new Exception(value + " is not allowed");
    }
    if (FieldType.ASCII_INTEGER.getXMLType().equals(type.getXMLType())) {
      if (asciiIntegerPattern.matcher(value).matches()) {
        try {
          Long.parseLong(value);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to long: " + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiIntegerPattern.toString() + "'");
      }
    } else if (FieldType.ASCII_NONNEGATIVE_INTEGER.getXMLType()
        .equals(type.getXMLType())) {
      if (asciiNonNegativeIntPattern.matcher(value).matches()) {
        try {
          UnsignedLong.valueOf(value);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to an unsigned long: "
              + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiNonNegativeIntPattern.toString() + "'");        
      }
    } else if (FieldType.ASCII_REAL.getXMLType().equals(type.getXMLType())) {
      if (asciiReal.matcher(value).matches()) {
        try {
          Double.parseDouble(value);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to a double: " + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiReal.toString() + "'");        
      }
    } else if (FieldType.ASCII_NUMERIC_BASE2.getXMLType()
        .equals(type.getXMLType())) {
      if (asciiNumericBase2Pattern.matcher(value).matches()) {
        try {
          new BigInteger(value, 2);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to a base-2 integer: "
              + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiNumericBase2Pattern.toString() + "'");
      }
    } else if (FieldType.ASCII_NUMERIC_BASE8.getXMLType()
        .equals(type.getXMLType())) {
      if (asciiNumericBase8Pattern.matcher(value).matches()) {
        try {
          new BigInteger(value, 8);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to a base-8 integer: "
              + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiNumericBase8Pattern.toString() + "'");
      }
    } else if (FieldType.ASCII_NUMERIC_BASE16.getXMLType()
        .equals(type.getXMLType())) {
      if (asciiNumericBase16Pattern.matcher(value).matches()) {
        try {
          new BigInteger(value, 16);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to a base-16 integer: "
              + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiNumericBase16Pattern.toString() + "'");
      }
    } else if (FieldType.ASCII_MD5_CHECKSUM.getXMLType()
        .equals(type.getXMLType())) {

      if (asciiMd5ChecksumPattern.matcher(value).matches()) {
        try {
          new BigInteger(value, 16);
        } catch (NumberFormatException e) {
          throw new Exception("Could not convert to a base-16 integer: "
              + value);
        }
      } else {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiMd5ChecksumPattern.toString() + "'");
      }
    } else if (FieldType.ASCII_ANYURI.getXMLType()
        .equals(type.getXMLType())) {
      try {
        URI uri = new URI(value);
      } catch (URISyntaxException e) {
        throw new Exception(e.getMessage());
      }
    } else if (FieldType.ASCII_DOI.getXMLType().equals(type.getXMLType())) {
      if (!asciiDoiPattern.matcher(value).matches()) {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiDoiPattern.toString() + "'");
      }
    } else if (FieldType.ASCII_LID.getXMLType().equals(type.getXMLType())) {
      if (!asciiLidPattern.matcher(value).matches()) {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiLidPattern.toString() + "'");
      }
    } else if (FieldType.ASCII_LIDVID.getXMLType().equals(type.getXMLType())) {
      if (!asciiLidVidPattern.matcher(value).matches()) {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiLidVidPattern.toString() + "'");
      }      
    } else if (FieldType.ASCII_LIDVID_LID.getXMLType()
        .equals(type.getXMLType())) {
      // Can accept a LID or LIDVID?
      if (!asciiLidVidLidPattern.matcher(value).matches()) {
        if (!asciiLidPattern.matcher(value).matches()) {
          throw new Exception("'" + value + "' does not match the patterns '"
              + asciiLidVidPattern.toString() + "' or '"
              + asciiLidPattern.toString() + "'");
        }
      }
    } else if (FieldType.ASCII_VID.getXMLType().equals(type.getXMLType())) {
      if (!asciiVidPattern.matcher(value).matches()) {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiVidPattern.toString() + "'");
      }
    } else if (FieldType.ASCII_STRING.getXMLType().equals(type.getXMLType())) {
      StringBuffer buffer = new StringBuffer(value);
      for (int i = 0; i < buffer.length(); i++) {
        if (buffer.charAt(i) > 127) {
          if (value.length() > 100) {
            value = value.substring(0, 100) + "...";
          }
          throw new Exception("'" + value + "' contains non-ASCII character: "
              + buffer.charAt(i));
        }
      }   
    } else if (FieldType.UTF8_STRING.getXMLType().equals(type.getXMLType())) {
      if (value.contains("\\s")) {
        if (value.length() > 100) {
          value = value.substring(0, 100) + "...";
        }        
        throw new Exception("'" + value + "' contains whitespace character(s)");
      }
    } else if (FieldType.ASCII_DATE_DOY.getXMLType()
        .equals(type.getXMLType()) || 
        FieldType.ASCII_DATE_TIME_DOY.getXMLType()
        .equals(type.getXMLType()) || 
        FieldType.ASCII_DATE_TIME_DOY_UTC.getXMLType()
        .equals(type.getXMLType()) ||
        FieldType.ASCII_DATE_TIME_YMD.getXMLType()
        .equals(type.getXMLType()) || 
        FieldType.ASCII_DATE_TIME_YMD_UTC.getXMLType()
        .equals(type.getXMLType()) ||
        FieldType.ASCII_DATE_YMD.getXMLType().equals(type.getXMLType()) ||
        FieldType.ASCII_TIME.getXMLType().equals(type.getXMLType())) {
      if(!DateTimeValidator.isValid(type, value)) {
        throw new Exception("Could not parse " + value
            + " using these patterns '"
            + DATE_TIME_VALID_FORMATS.get(type.getXMLType()) + "'");
      }
    } else if (FieldType.ASCII_DIRECTORY_PATH_NAME.getXMLType().equals(
        type.getXMLType())) {
      String[] dirs = value.split("/");
      for (int i = 0; i < dirs.length; i++) {
        if (!asciiDirPathNamePattern.matcher(dirs[i]).matches()) {
          throw new Exception(dirs[i] + " does not match the pattern '"
              + asciiDirPathNamePattern.toString() + "'");
        }
        if (dirs[i].length() > 255) {
          throw new Exception(dirs[i] + " is longer than 255 characters");
        }
      }
    } else if (FieldType.ASCII_FILE_NAME.getXMLType()
        .equals(type.getXMLType())) {
      if (!asciiFileNamePattern.matcher(value).matches()) {
        throw new Exception(value + " does not match the pattern '"
            + asciiFileNamePattern.toString() + "'");        
      }
      if (value.length() > 255) {
        throw new Exception(value + " is longer than 255 characters");
      }      
    } else if (FieldType.ASCII_FILE_SPECIFICATION_NAME.getXMLType()
        .equals(type.getXMLType())) {
      String dir = FilenameUtils.getFullPath(value);
      if (!dir.isEmpty()) {
        if (dir.length() > 255) {
          throw new Exception("The directory spec '" + dir
              + "' is longer than 255 characters");
        }
        if (!dirPattern.matcher(dir).matches()) {
          throw new Exception("The directory spec '" + dir
              + "' does not match the pattern '" + dirPattern + "'");  
        }
      }
      String name = FilenameUtils.getName(value);
      if (name.isEmpty()) {
        throw new Exception("No filename spec found in '" + value + "'."); 
      } else if (!asciiFileNamePattern.matcher(name).matches()) {
        throw new Exception("The filename spec '" + name
            + "' does not match the pattern '"
            + asciiFileNamePattern.toString() + "'");       
      }
      if (name.length() > 255) {
        throw new Exception("The filename spec '" + name
            + "' is longer than 255 characters");
      }
    } else if (FieldType.ASCII_BIBCODE.getXMLType().equals(
        type.getXMLType())) {
      if (!asciiBibCodePattern.matcher(value).matches()) {
        throw new Exception("'" + value + "' does not match the pattern '"
            + asciiBibCodePattern.toString() + "'");
      }
    }
  }
  
  /**
   * Check that the given value matches the defined field format.
   * 
   * @param value The value to check.
   * @param format The defined field format.
   * @param fieldIndex Where the field value is located.
   * @param recordLocation The record location where the field is located.
   */
  private void checkFormat(String value, String format, int fieldIndex, 
      RecordLocation recordLocation) {
    Matcher matcher = formatPattern.matcher(format);
    int precision = -1;
    boolean isValid = true;
    if (matcher.matches()) {
      int width = Integer.parseInt(matcher.group(2));
      if (matcher.group(4) != null) {
        precision = Integer.parseInt(matcher.group(4));
      }
      String specifier = matcher.group(5);
      if (matcher.group(1) != null) {
        String justified = matcher.group(1);
        if ("+".equals(justified)) {
          //check if there is trailing whitespace
          if (trailingWhiteSpacePattern.matcher(value).matches()) {
            addTableProblem(ExceptionType.ERROR,
                ProblemType.FIELD_VALUE_NOT_RIGHT_JUSTIFIED,
                    "The value '" + value + "' is not right-justified.",
                    recordLocation,
                    fieldIndex);
            isValid = false;
          }
        } else if ("-".equals(justified)) {
          if (leadingWhiteSpacePattern.matcher(value).matches()) {
            addTableProblem(ExceptionType.ERROR,
                ProblemType.FIELD_VALUE_NOT_LEFT_JUSTIFIED,
                "The value '" + value + "' is not left-justified.",
                recordLocation,
                fieldIndex);    
            isValid = false;
          }
        }
      }
      try {
        if (specifier.matches("[eE]")) {
          String p = "(\\+|-)?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([Ee](\\+|-)?[0-9]+)";
          if (value.trim().matches(p)) {
            Double.parseDouble(value.trim());
          } else {
            throw new NumberFormatException("Value does not match pattern.");
          }
        } else if (specifier.equals("f")) {
          String p = "(\\+|-)?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)";
          if (value.trim().matches(p)) {
            Double.parseDouble(value.trim());
          } else {
            throw new NumberFormatException("Value does not match pattern.");
          }
        } else if (specifier.equals("d")) {
          BigInteger bi = new BigInteger(value.trim());
        } else if (specifier.equals("o")) {
          BigInteger bi = new BigInteger(value.trim());
          if (bi.signum() == -1) {
            throw new NumberFormatException("Value must be unsigned.");
          }
        } else if (specifier.equals("x")) {
          BigInteger bi = new BigInteger(value.trim());
          if (bi.signum() == -1) {
            throw new NumberFormatException("Value must be unsigned.");
          }
        }
      } catch (NumberFormatException e) {
        addTableProblem(ExceptionType.ERROR,
            ProblemType.FIELD_VALUE_FORMAT_SPECIFIER_MISMATCH,
            "The value '" + value.trim() + "' does not match the "
                + "defined field format specifier '" + specifier + "': "
                + e.getMessage(),
            recordLocation,
            fieldIndex);
      }
      if (value.trim().length() > width) {
        addTableProblem(ExceptionType.ERROR,
            ProblemType.FIELD_VALUE_TOO_LONG,
            "The length of the value '" + value.trim() + "' exceeds the max "
                + "width set in the defined field format "
                + "(max " + width + ", got " + value.trim().length() + ").",
             recordLocation,
             fieldIndex);
        isValid = false;
      }
      if (precision != -1) {
        if (specifier.matches("[feE]")) {
          String[] tokens = value.trim().split("[eE]", 2);
          int length = 0;
          if (tokens[0].indexOf(".") != -1) {
            length = tokens[0].substring(tokens[0].indexOf(".") + 1).length();
          }
          if (length != precision) {
            addTableProblem(ExceptionType.ERROR,
                ProblemType.FIELD_VALUE_FORMAT_PRECISION_MISMATCH,
                "The number of digits to the right of the decimal point "
                    + "in the value '" + value.trim() + "' does not equal the "
                    + "precision set in the defined field format "
                    + "(expected " + precision + ", got " + length + ").",
                recordLocation,
                fieldIndex);
            isValid = false;
          }
        }
      }
      if (isValid) {
        addTableProblem(ExceptionType.DEBUG,
            ProblemType.FIELD_VALUE_FORMAT_MATCH,
            "Value '" + value + "' conforms to the defined field format '"
                + format + "'",
            recordLocation, 
            fieldIndex);
      }
    }
  }
  
  /**
   * Adds a TableContentException to the Exception Container.
   * 
   * @param exceptionType The severity.
   * @param message The exception message.
   * @param recordLocation The record location where the field is located.
   * @param field The index of the field.
   */
  private void addTableProblem(ExceptionType exceptionType, 
      ProblemType problemType, String message, 
      RecordLocation recordLocation, int field) {
    listener.addProblem(
        new TableContentProblem(exceptionType,
            problemType,
            message,
            recordLocation.getDataFile(),
            recordLocation.getLabel(),
            recordLocation.getTable(),
            recordLocation.getRecord(),
            field));
  }
}
