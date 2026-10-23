"""Measured Structured Streaming backlog using a fixed tiny text classifier.

File-source isolation experiment: excludes Kafka and serving API overhead.
Injected delay is labeled separately from actual classifier work.
"""
import json
import os
from pathlib import Path
import statistics
import tempfile
import threading
import time

from pyspark.sql import SparkSession
from pyspark.sql.types import StructType, StructField, StringType, DoubleType, LongType
from sklearn.pipeline import make_pipeline
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.naive_bayes import MultinomialNB

TEXTS=['database query schema','football match goal','database table index','football team stadium']
LABELS=['technology','sport','technology','sport']
MODEL=make_pipeline(CountVectorizer(),MultinomialNB()).fit(TEXTS,LABELS)


def experiment(spark,name,period,delay):
    with tempfile.TemporaryDirectory(prefix='spark-talk-') as temp:
        root=Path(temp); source=root/'input'; source.mkdir()
        rows=[]; batches=[]
        schema=StructType([StructField('event_id',LongType()),StructField('text',StringType()),StructField('ingress',DoubleType())])
        stream=spark.readStream.schema(schema).option('maxFilesPerTrigger',1).json(str(source))

        def batch(frame,batch_id):
            start=time.time()
            records=frame.collect()
            for record in records:
                prediction=str(MODEL.predict([record.text])[0])
                if delay: time.sleep(delay)
                end=time.time()
                rows.append({'id':record.event_id,'prediction':prediction,'ingress':record.ingress,
                             'processing_start':start,'result_visible':end,'age_seconds':end-record.ingress})
            batches.append({'id':batch_id,'count':len(records),'duration_seconds':time.time()-start})

        query=stream.writeStream.foreachBatch(batch).option('checkpointLocation',str(root/'checkpoint')).trigger(processingTime='1 second').start()
        try:
            # Warm JVM/query machinery before recording the measurement window.
            warm=root/'warm.tmp'; warm.write_text(json.dumps({'event_id':-1,'text':TEXTS[0],'ingress':time.time()})+'\n')
            warm.rename(source/'warm.json')
            query.processAllAvailable(); rows.clear(); batches.clear()
            begin=time.time()
            for i in range(12):
                temporary=root/f'{i}.tmp'
                temporary.write_text(json.dumps({'event_id':i,'text':TEXTS[i%4],'ingress':time.time()})+'\n')
                temporary.rename(source/f'{i}.json')
                time.sleep(period)
            offered_end=time.time()
            completed_at_end=len(rows)
            query.processAllAvailable()
            finish=time.time()
            assert {r['id'] for r in rows}==set(range(12)),rows
            assert all(r['prediction']==LABELS[r['id']%4] for r in rows)
            ages=sorted(r['age_seconds'] for r in rows)
            return {'case':name,'source':'file stream, maxFilesPerTrigger=1', 'events':12,
                    'input_interval_seconds':period,'injected_per_record_delay_seconds':delay,
                    'offered_rate':12/(offered_end-begin),'processed_by_input_end':completed_at_end,
                    'pending_at_input_end':12-completed_at_end,'drain_seconds':finish-offered_end,
                    'median_age_seconds':statistics.median(ages),'max_age_seconds':max(ages),
                    'batches':batches,'events_observed':rows}
        finally: query.stop()


spark=SparkSession.builder.appName('prediction-age-experiment').config('spark.sql.shuffle.partitions','2').getOrCreate()
spark.sparkContext.setLogLevel('ERROR')
try:
    results=[experiment(spark,'baseline',1.2,0),experiment(spark,'overload',.1,1.2),
             experiment(spark,'same-input-delay-removed',.1,0)]
    report={'spark':spark.version,'model':'CountVectorizer + MultinomialNB on four explicit fixture texts',
            'resources':'local[2]','trigger_seconds':1,'repetitions':1,
            'boundary':'real Spark processing; file input and collected result visibility, not Kafka/HTTP or production benchmark',
            'results':results}
    Path('/lab/results.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps([{k:v for k,v in r.items() if k not in ('batches','events_observed')} for r in results],indent=2))
finally: spark.stop()
