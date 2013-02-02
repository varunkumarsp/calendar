/*
 * Copyright (C) 2003-2013 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.calendar.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

import org.exoplatform.calendar.service.Calendar;
import org.exoplatform.calendar.service.CalendarEvent;
import org.exoplatform.calendar.service.CalendarService;
import org.exoplatform.calendar.service.EventQuery;
import org.exoplatform.calendar.service.GroupCalendarData;
import org.exoplatform.calendar.service.Utils;
import org.exoplatform.commons.api.search.SearchServiceConnector;
import org.exoplatform.commons.api.search.data.SearchResult;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.jcr.impl.core.query.QueryImpl;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.security.ConversationState;

/**
 * Created by The eXo Platform SAS
 * Author : eXoPlatform
 *          exo@exoplatform.com
 * Jan 22, 2013  
 */
public class CalendarSearchServiceConnector extends SearchServiceConnector {


  private NodeHierarchyCreator nodeHierarchyCreator_;
  private CalendarService calendarService_;
  private OrganizationService organizationService_;

  private static final Log     log                 = ExoLogger.getLogger("cs.calendar.unified.search.service");
  private Map<String, String> calendarMap = new HashMap<String, String>();



  public CalendarSearchServiceConnector(InitParams initParams) {
    super(initParams);
    nodeHierarchyCreator_  = (NodeHierarchyCreator) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(NodeHierarchyCreator.class);
    calendarService_  = (CalendarService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(CalendarServiceImpl.class);
    organizationService_ = (OrganizationService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(OrganizationService.class);
  }


  @Override
  public Collection<SearchResult> search(String query,
                                         Collection<String> sites,
                                         int offset,
                                         int limit,
                                         String sort,
                                         String order) {
    return searchData(null, query, sites, offset, limit, sort, order);

  }


  protected Collection<SearchResult> searchData(String dataType, String query,
                                                Collection<String> sites,
                                                int offset,
                                                int limit,
                                                String sort,
                                                String order) {
    List<SearchResult> events = new ArrayList<SearchResult>();
    try {
      String userId = ConversationState.getCurrent().getIdentity().getUserId() ;
      Node calendarHome = nodeHierarchyCreator_.getUserApplicationNode(SessionProvider.createSystemProvider(), userId);

      List<Calendar> calendars = calendarService_.getUserCalendars(userId, true);
      GroupCalendarData sharedCalendar = calendarService_.getSharedCalendars(userId, true) ;
      if(sharedCalendar != null) calendars.addAll(sharedCalendar.getCalendars());
      Collection<Group> group = organizationService_.getGroupHandler().findGroupsOfUser(userId);
      if(!group.isEmpty()) {
        String[] groupIds = new String[group.size()];
        int i = 0 ;
        for(Group g : group) {
          groupIds[i] = g.getId() ;
          i++;
        }
        List<GroupCalendarData> groupCalendar = calendarService_.getGroupCalendars(groupIds, true, userId) ;
        if(groupCalendar != null) 
          for(GroupCalendarData gCal : groupCalendar){
            if(gCal.getCalendars() != null) calendars.addAll(gCal.getCalendars());
          }
      }
      for(Calendar cal : calendars){
        calendarMap.put(cal.getId(), cal.getName()) ;
      }

      EventQuery eventQuery = new UnifiedQuery(); 
      eventQuery.setQueryType(Query.SQL);
      eventQuery.setEventType(dataType);
      eventQuery.setText(query) ;
      eventQuery.setOrderBy(new String[]{Utils.sortFieldsMap.get(sort)});
      eventQuery.setOrderType(order);
      if(CalendarEvent.TYPE_TASK.equals(dataType))
      eventQuery.setState(CalendarEvent.COMPLETED);
      //log.info("\n -------" + eventQuery.getQueryStatement() + "\n") ;
      QueryManager qm = calendarHome.getSession().getWorkspace().getQueryManager();
      QueryImpl jcrquery = (QueryImpl)qm.createQuery(eventQuery.getQueryStatement(), eventQuery.getQueryType());
      jcrquery.setOffset(offset);
      jcrquery.setLimit(limit);
      QueryResult result = jcrquery.execute();
      /*
      NodeIterator it = result.getNodes();
      while (it.hasNext()) {
        events.add(getResult(it.nextNode()));
      }
       */
      RowIterator rIt = result.getRows();
      while (rIt.hasNext()) {
        events.add(buildResult(dataType, rIt.nextRow()));
      }
    }
    catch (Exception e) {
      log.info("Could not execute unified seach " + dataType , e) ; 
    }
    return events;

  }

  private SearchResult buildResult(String dataType, Object iter) {
    try {
      StringBuffer detail = new StringBuffer();
      String title = buildValue(Utils.EXO_SUMMARY, iter);
      detail.append(buildCalName(Utils.EXO_CALENDAR_ID, iter)) ; 
      String url = Utils.SLASH + Utils.DETAIL_PATH + Utils.SLASH + buildValue(Utils.EXO_ID, iter);
      String excerpt = buildExcerpt(iter);
      String detailValue = Utils.EMPTY_STR;
      String imageUrl = buildImageUrl(iter);
      detail.append(buildDetail(iter));
      if(detail.length() > 0) detailValue = detail.toString();
      long relevancy = buildScore(iter);
      long date = buildDate(iter) ;
      CalendarSearchResult result = new CalendarSearchResult(url, title, excerpt, detailValue, imageUrl, date, relevancy);
      result.setDataType(dataType);
      if(CalendarEvent.TYPE_EVENT.equals(dataType)){
        result.setFromDateTime(buildDate(iter, Utils.EXO_FROM_DATE_TIME));
      }
      return result;
    }catch (Exception e) {
      log.info("Error when getting property from node " + e);
    }
    return null;
  }

  private String buildExcerpt(Object iter) throws RepositoryException{
    if(iter instanceof Row){
      Row row = (Row) iter;
      if(row.getValue(Utils.JCR_EXCERPT_ROW) != null) {
       String origin = (row.getValue(Utils.JCR_EXCERPT_ROW).getString());
         origin.replace(row.getValue(Utils.EXO_CALENDAR_ID).getString(), "").replace(row.getValue(Utils.EXO_ID).getString(),"");
        return origin;
      }
    }
    return Utils.EMPTY_STR;
  }


  private String buildImageUrl(Object iter) throws RepositoryException{
    String icon = null;
    if(iter instanceof Row){
      Row row = (Row) iter;
      if(row.getValue(Utils.EXO_EVENT_TYPE) != null)
        if(CalendarEvent.TYPE_TASK.equals(row.getValue(Utils.EXO_EVENT_TYPE).getString())) 
          icon = row.getValue(Utils.EXO_EVENT_STATE).getString();
        else icon = Utils.EVENT_ICON; 
    } else {
      Node eventNode = (Node) iter;
      if(eventNode.hasProperty(Utils.EXO_EVENT_TYPE)){
        if(CalendarEvent.TYPE_TASK.equals(eventNode.getProperty(Utils.EXO_EVENT_TYPE).getString())) 
        {
          if(eventNode.hasProperty(Utils.EXO_EVENT_STATE))
            icon = eventNode.getProperty(Utils.EXO_EVENT_STATE).getString();
        } else icon = Utils.EVENT_ICON;
      }
    }
    return icon;
  }

  private long buildDate(Object iter) {
    try {
      return buildDate(iter, Utils.EXO_DATE_CREATED).getTimeInMillis();
    } catch (Exception e) {
      log.info("Clould not build date value to long from data " + e);
      return 0;
    }
  }


  private java.util.Calendar buildDate(Object iter, String readProperty){
    try {
      if(iter instanceof Row){
        Row row = (Row) iter;
        return row.getValue(readProperty).getDate();
      } else {
        Node eventNode = (Node) iter;
        if(eventNode.hasProperty(readProperty)){
          return eventNode.getProperty(readProperty).getDate();
        } else {
          return null ;
        }
      }
    } catch (Exception e) {
      log.info("Could not build date value from " + readProperty + " : " + e);
      return null;
    }
  }


  private Object buildCalName(String property, Object iter) throws RepositoryException{
    if(iter instanceof Row){
      Row row = (Row) iter;
      if(row.getValue(property) != null) return calendarMap.get(row.getValue(property).getString()) ;
    } else {
      Node eventNode = (Node) iter;
      if(eventNode.hasProperty(property)){
        return calendarMap.get(eventNode.getProperty(property).getString());
      }
    }
    return Utils.EMPTY_STR;
  }


  private long buildScore(Object iter){
    try {
      if(iter instanceof Row){
        Row row = (Row) iter;
        return row.getValue(Utils.JCR_SCORE).getLong() ;
      }
    } catch (Exception e) {
      log.info("No score return by query " + e);
    }
    return 0;
  }

  private String buildValue(String property, Object iter) throws RepositoryException{
    if(iter instanceof Row){
      Row row = (Row) iter;
      if(row.getValue(property) != null) return row.getValue(property).getString() ;
    } else {
      Node eventNode = (Node) iter;
      if(eventNode.hasProperty(property)){
        return eventNode.getProperty(property).getString();
      }
    } 
    return Utils.EMPTY_STR;
  }

  private String buildDetail(Object iter) throws RepositoryException{
    SimpleDateFormat df = new SimpleDateFormat(Utils.DATE_TIME_FORMAT) ;
    StringBuffer detail = new StringBuffer();
    if(iter instanceof Row){
      Row row = (Row) iter;
      if(row.getValue(Utils.EXO_EVENT_TYPE) != null)
        if(CalendarEvent.TYPE_EVENT.equals(row.getValue(Utils.EXO_EVENT_TYPE).getString())) {
          if(row.getValue(Utils.EXO_FROM_DATE_TIME) != null)
            detail.append(Utils.MINUS).append(df.format(row.getValue(Utils.EXO_FROM_DATE_TIME).getDate().getTime())) ;
          if(row.getValue(Utils.EXO_LOCATION) != null)
            detail.append(Utils.MINUS).append(row.getValue(Utils.EXO_LOCATION).getString()) ;
        } else {
          if(row.getValue(Utils.EXO_TO_DATE_TIME) != null)
            detail.append(Utils.MINUS).append(Utils.DUE_FOR).append(df.format(row.getValue(Utils.EXO_TO_DATE_TIME).getDate().getTime()));
        }
    } else {
      Node eventNode = (Node) iter;
      if(eventNode.hasProperty(Utils.EXO_EVENT_TYPE)){
        if(CalendarEvent.TYPE_EVENT.equals(eventNode.getProperty(Utils.EXO_EVENT_TYPE).getString())) {
          if(eventNode.hasProperty(Utils.EXO_FROM_DATE_TIME)) {
            detail.append(Utils.MINUS).append(df.format(eventNode.getProperty(Utils.EXO_FROM_DATE_TIME).getDate().getTime())) ;
          }
          if(eventNode.hasProperty(Utils.EXO_LOCATION)) {
            detail.append(Utils.MINUS).append(eventNode.getProperty(Utils.EXO_LOCATION).getString()) ;
          }
        } else {
          if(eventNode.hasProperty(Utils.EXO_TO_DATE_TIME)) {
            detail.append(Utils.MINUS).append(Utils.DUE_FOR).append(df.format(eventNode.getProperty(Utils.EXO_TO_DATE_TIME).getDate().getTime())) ;
          }
        }
      }
    }  
    return detail.toString();
  }
}
