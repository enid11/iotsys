/*******************************************************************************
 * Copyright (c) 2013
 * Institute of Computer Aided Automation, Automation Systems Group, TU Wien.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the IoTSyS project.
 ******************************************************************************/

package at.ac.tuwien.auto.iotsys.gateway.obix.objects;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Logger;

import obix.Abstime;
import obix.Bool;
import obix.Contract;
import obix.Err;
import obix.Feed;
import obix.Int;
import obix.Obj;
import obix.Op;
import obix.Real;
import obix.Ref;
import obix.Reltime;
import obix.Str;
import obix.Uri;
import obix.contracts.History;
import obix.contracts.HistoryAppendIn;
import obix.contracts.HistoryFilter;
import obix.contracts.HistoryRecord;
import obix.contracts.HistoryRollupIn;
import at.ac.tuwien.auto.iotsys.commons.OperationHandler;
import at.ac.tuwien.auto.iotsys.gateway.obix.objectbroker.ObjectBrokerImpl;
import at.ac.tuwien.auto.iotsys.gateway.obix.observer.Observer;
import at.ac.tuwien.auto.iotsys.gateway.obix.observer.Subject;

/**
 * Generic history implementation. Should only be used for basic value types
 * (bool, int, real, str).
 */
public class HistoryImpl extends Obj implements History, Observer {
	private static final Logger log = Logger.getLogger(HistoryImpl.class.getName());
	
	public static final String HISTORY_CONTRACT = "obix:History";

	private Int count = new Int();
	private Abstime start = new Abstime(null);
	private Abstime end = new Abstime(null);
	private Str tz = new Str(TimeZone.getDefault().getID());
	private Op query = new Op();
	private HistoryFeed feed;
	private Op rollup = new Op();
	private Op append = new Op();

	private Obj observedDatapoint;

	public HistoryImpl(Obj observedDatapoint, int historyCountMax) {
		this.observedDatapoint = observedDatapoint;

		this.setName("history");
		this.setHref(new Uri("history"));
		this.setIs(new Contract(HISTORY_CONTRACT));

		count.setName("count");
		count.setHref(new Uri("count"));

		start.setName("start");
		start.setHref(new Uri("start"));

		end.setName("end");
		end.setHref(new Uri("end"));
		
		tz.setName("tz");
		tz.setHref(new Uri("tz"));

		observedDatapoint.attach(this);

		add(count);
		add(start);
		add(end);
		add(tz);
		
		feed = new HistoryFeed(historyCountMax);
		feed.setIn(new Contract("obix:HistoryFilter"));
		feed.setOf(new Contract("obix:HistoryRecord"));
		feed.setHref(new Uri("feed"));
		feed.setName("feed");
		add(feed);

		query.setName("query");
		query.setIn(new Contract(HistoryFilterImpl.HISTORY_FILTER_CONTRACT));
		query.setOut(new Contract(
				HistoryQueryOutImpl.HISTORY_QUERY_OUT_CONTRACT));
		add(query);

		rollup.setName("rollup");
		rollup.setIn(new Contract(HistoryRollupInImpl.HISTORY_ROLLUPIN_CONTRACT));
		rollup.setOut(new Contract(
				HistoryRollupOutImpl.HISTORY_ROLLUPOUT_CONTRACT));
		add(rollup);
		
		append.setName("append");
		append.setIn(new Contract(HistoryAppendIn.HISTORY_APPENDIN_CONTRACT));
		append.setOut(new Contract(HistoryAppendOutImpl.HISTORY_APPENDOUT_CONTRACT));
		add(append);
	}

	@Override
	public void initialize() {
		this.setHref(new Uri(observedDatapoint.getFullContextPath()
				+ "/history"));
		ObjectBrokerImpl.getInstance().addObj(this, false);
		
		String queryHref = observedDatapoint.getFullContextPath()
				+ "/history/query";

		ObjectBrokerImpl.getInstance().addOperationHandler(

		new Uri(queryHref), new OperationHandler() {
			public Obj invoke(Obj in) {
				return HistoryImpl.this.query(in);
			}
		});

		String rollupHref = observedDatapoint.getFullContextPath()
				+ "/history/rollup";

		ObjectBrokerImpl.getInstance().addOperationHandler(new Uri(rollupHref),
				new OperationHandler() {
					public Obj invoke(Obj in) {
						return HistoryImpl.this.rollup(in);
					}
				});
		
		String appendHref = observedDatapoint.getFullContextPath()
				+ "/history/append";

		ObjectBrokerImpl.getInstance().addOperationHandler(new Uri(appendHref),
				new OperationHandler() {
					public Obj invoke(Obj in) {
						return HistoryImpl.this.append(in);
					}
				});

		// add history reference in the parent element
		if (observedDatapoint.getParent() != null) {
			Ref ref = new Ref(observedDatapoint.getName() + " history", new Uri(
					observedDatapoint.getHref() + "/history"));
			ref.setIs(new Contract(HISTORY_CONTRACT));
			observedDatapoint.getParent().add(ref);
		}
	}

	private Obj query(Obj in) {
		HistoryFilter filter = (HistoryFilter) in;
		return new HistoryQueryOutImpl(feed.filterRecords(feed.getEvents(), filter));
	}

	private Obj rollup(Obj in) {
		if (!observedDatapoint.isInt() && !observedDatapoint.isReal()) {
			Err notSupported = new Err("Rollup only supported on numeric values");
			notSupported.setIs(new Contract("obix:UnsupportedErr"));
			return notSupported;
		}
		
		long limit = 0;

		Abstime start = new Abstime();
		Abstime end = new Abstime();
		Reltime interval = new Reltime(60);
		if (in != null && in instanceof HistoryRollupIn) {
			HistoryRollupIn rollupIn = (HistoryRollupIn) in;
			limit = rollupIn.limit().get();
			start = rollupIn.start();
			end = rollupIn.end();
			interval = rollupIn.interval();
		}

		long ival = interval.get();
		long curInterval = start.get();
		
		if (ival <= 0) {
			return new Err("Invalid interval");
		}
		
		ArrayList<HistoryRollupRecordImpl> rollups = new ArrayList<HistoryRollupRecordImpl>();

		ArrayList<HistoryRecordImpl> currentInterval = new ArrayList<HistoryRecordImpl>();
		
		List<Obj> records = feed.getEvents();
		int i = 0;
		
		while (i < records.size()) {
			HistoryRecordImpl record = (HistoryRecordImpl) records.get(i);
			
			// record before start time
			if (record.timestamp().get() <= curInterval) {
				i++;
				continue;
			}
			
			if (record.timestamp().get() <= curInterval + ival) {
				// record inside interval
				currentInterval.add(record);
				i++;
			} else {
				// record belonging to next interval
				
				// close current interval
				if (!currentInterval.isEmpty()) {
					HistoryRollupRecordImpl rollupRecord = createRecord(currentInterval, curInterval, curInterval + ival, start.getTimeZone());
					rollups.add(rollupRecord);
					currentInterval.clear();
					
					// rollup record limit reached
					if (limit != 0 && rollups.size() >= limit) break;
				}
				
				// advance interval
				curInterval += ival;
			}
		}
		
		// close last interval
		if(currentInterval.size() > 0) {
			HistoryRollupRecordImpl rollupRecord = createRecord(currentInterval, curInterval , curInterval + ival, start.getTimeZone());
			rollups.add(rollupRecord);
		}
		
		HistoryRollupOutImpl historyRollupOutImpl = new HistoryRollupOutImpl(rollups);
		historyRollupOutImpl.count().set(rollups.size());
		historyRollupOutImpl.start().set(start.get(), start.getTimeZone());
		historyRollupOutImpl.end().set(end.get(), end.getTimeZone());

		return historyRollupOutImpl;
	}
	
	private Obj append(Obj in) {
		HistoryAppendOutImpl historyAppendOut = null;
		
		obix.List records;
		
		if (in != null && in instanceof HistoryAppendIn) {
			HistoryAppendIn appendIn = (HistoryAppendIn) in;
			records = appendIn.data();
		} else {
			records = new obix.List();
		}
		
		Abstime timestamp = end;
		ArrayList<HistoryRecordImpl> newRecords = new ArrayList<HistoryRecordImpl>();
		
		long now = Calendar.getInstance().getTimeInMillis();
		
		for (Obj record : records.list()) {
			HistoryRecord historyRecord = (HistoryRecord) record;
			newRecords.add(new HistoryRecordImpl(historyRecord));
			
			if (historyRecord.timestamp().compareTo(timestamp) != 1) {
				// The HistoryRecords in the data list MUST be sorted by timestamp from oldest to newest,
				// and MUST not include a timestamp equal to or older than History.end
				return new Err("Records out of order");
			}
			
			if (historyRecord.timestamp().getMillis() > now) {
				// history record with future time
				log.info(getFullContextPath() + ": Appending future event!");
			}
			
			timestamp = historyRecord.timestamp();
		}
		
		
		for (HistoryRecordImpl record : newRecords) {
			feed.addEvent(record);
		}
		
		updateKids();
		
		historyAppendOut = new HistoryAppendOutImpl(newRecords, feed.getRecords());
		return historyAppendOut;
	}
	
	private HistoryRollupRecordImpl createRecord(ArrayList<HistoryRecordImpl> currentInterval, long start, long stop, TimeZone tz){
		HistoryRollupRecordImpl rollupRecord = new HistoryRollupRecordImpl();
		rollupRecord.count().set(currentInterval.size());
		
		double sum = 0;
		double min = Double.NaN;
		double max = Double.NaN;
		double avg = Double.NaN;

		for (HistoryRecordImpl historyRecord : currentInterval) {

			double curVal = 0.0;
			if (historyRecord.value().isReal()) {
				curVal = historyRecord.value().getReal();
			}

			if (historyRecord.value().isInt()) {
				curVal = historyRecord.value().getInt();
			}

			sum += curVal;

			if (Double.isNaN(min)) {
				min = curVal;
			}

			if (Double.isNaN(max)) {
				max = curVal;
			}

			if (curVal < min) {
				min = curVal;
			}

			if (curVal > max) {
				max = curVal;
			}
		}

		avg = sum / currentInterval.size();

		rollupRecord.min().set(min);
		rollupRecord.max().set(max);
		rollupRecord.avg().set(avg);
		rollupRecord.sum().set(sum);

		rollupRecord.start().set(start, tz);
		rollupRecord.end().set(stop, tz);
		return rollupRecord;
	}

	public Int count() {
		return count;
	}

	public Abstime start() {
		return start;
	}

	public Abstime end() {
		return end;
	}

	public Str tz() {
		return tz;
	}

	public Op query() {
		return query;
	}

	public Feed feed() {
		return feed;
	}

	public Op rollup() {
		return rollup;
	}

	@Override
	public Op append() {
		return append;
	}

	/**
	 * Observer method, that is called if the parent object changes
	 */
	@Override
	public void update(Object state) {
		if (state instanceof Obj) {
			HistoryRecordImpl historyRecordImpl = new HistoryRecordImpl(
					new Obj());
			// only allow basic value types
			if (state instanceof Bool) {
				historyRecordImpl = new HistoryRecordImpl(new Bool(
						((Bool) state).get()));
			}

			if (state instanceof Real) {
				historyRecordImpl = new HistoryRecordImpl(new Real(
						((Real) state).get()));
			}

			if (state instanceof Int) {
				historyRecordImpl = new HistoryRecordImpl(new Int(
						((Int) state).get()));
			}

			if (state instanceof Str) {
				historyRecordImpl = new HistoryRecordImpl(new Str(
						((Str) state).get()));
			}

			feed.addEvent(historyRecordImpl);

			updateKids();
		}
	}
	
	/**
	 * Update start, end and count after insertion of HistoryRecords
	 */
	private void updateKids() {
		List<Obj> events = feed.getEvents();
		HistoryRecordImpl firstRecord = (HistoryRecordImpl) events.get(0);
		HistoryRecordImpl lastRecord = (HistoryRecordImpl) events.get(events.size()-1);
		
		count.setSilent(events.size());
		this.start.set(firstRecord.timestamp().get(), TimeZone
				.getTimeZone((firstRecord.timestamp().getTz())));
		this.end.set(lastRecord.timestamp().get(), TimeZone
				.getTimeZone((lastRecord.timestamp().getTz())));
	}

	public void setSubject(Subject object) {

	}

	public Subject getSubject() {
		return null;
	}

}
