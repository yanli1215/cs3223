/**
 * Block Nested Join algorithm
 **/

package qp.operators;

import qp.utils.Attribute;
import qp.utils.Batch;
import qp.utils.Condition;
import qp.utils.Tuple;

import java.io.*;
import java.util.ArrayList;

public class BlockNestedJoin extends NestedJoin {

    Batch[] leftbatches;                // Buffer page for left input stream

    public BlockNestedJoin(Join jn) {
        super(jn);
    }

    /**
     * During open finds the index of the join attributes
     * * Materializes the right hand side into a file
     * * Opens the connections
     **/
    public boolean open() {
        /** select number of tuples per batch **/
        int tuplesize = schema.getTupleSize();
        batchsize = Batch.getPageSize() / tuplesize;

        /** find indices attributes of join conditions **/
        leftindex = new ArrayList<>();
        rightindex = new ArrayList<>();
        for (Condition con : conditionList) {
            Attribute leftattr = con.getLhs();
            Attribute rightattr = (Attribute) con.getRhs();
            leftindex.add(left.getSchema().indexOf(leftattr));
            rightindex.add(right.getSchema().indexOf(rightattr));
        }
        Batch rightpage;

        /** initialize the cursors of input buffers **/
        lcurs = 0;
        rcurs = 0;
        eosl = false;
        /** because right stream is to be repetitively scanned
         ** if it reached end, we have to start new scan
         **/
        eosr = true;

        /** Right hand side table is to be materialized
         ** for the Block Nested join to perform
         **/
        if (!right.open()) {
            return false;
        } else {
            /** If the right operator is not a base table then
             ** Materialize the intermediate result from right
             ** into a file
             **/
            filenum++;
            rfname = "BNJtemp-" + filenum;
            try {
                ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(rfname));
                while ((rightpage = right.next()) != null) {
                    out.writeObject(rightpage);
                }
                out.close();
            } catch (IOException io) {
                System.out.println("BlockNestedJoin: Error writing to temporary file");
                return false;
            }
            if (!right.close())
                return false;
        }
        return left.open();
    }

    /**
     * from input buffers selects the tuples satisfying join condition
     * * And returns a page of output tuples
     **/
    public Batch next() {
        int i, j;
        if (eosl) {
            return null;
        }
        outbatch = new Batch(batchsize);
        while (!outbatch.isFull()) {
            if (lcurs == 0 && eosr) {
                /** new left page is to be fetched**/
                leftbatches = new Batch[numBuff - 2];
                leftbatches[0] = left.next();
                if (leftbatches[0] == null) {
                    eosl = true;
                    return outbatch;
                }
                for (i = 1; i < leftbatches.length; i++) {
                    leftbatches[i] = left.next();
                    if (leftbatches[i] == null) {
                        break;
                    }
                }
                /** Whenever a new left page came, we have to start the
                 ** scanning of right table
                 **/
                try {
                    in = new ObjectInputStream(new FileInputStream(rfname));
                    eosr = false;
                } catch (IOException io) {
                    System.err.println("BlockNestedJoin:error in reading the file");
                    System.exit(1);
                }

            }
            int leftTupleSize = 0;
            for (Batch leftBatch : leftbatches) {
                if (leftBatch == null) {
                    break;
                }
                leftTupleSize += leftBatch.size();
            }
            while (!eosr) {
                try {
                    if (rcurs == 0 && lcurs == 0) {
                        rightbatch = (Batch) in.readObject();
                    }
                    for (i = lcurs; i < leftTupleSize; ++i) {
                        int leftBatchIndex = i / leftbatches[0].size();
                        int leftTupleIndex = i % leftbatches[0].size();
                        Tuple lefttuple = leftbatches[leftBatchIndex].get(leftTupleIndex);

                        for (j = rcurs; j < rightbatch.size(); ++j) {
                            Tuple righttuple = rightbatch.get(j);

                            if (lefttuple.checkJoin(righttuple, leftindex, rightindex, conditionList)) {
                                Tuple outtuple = lefttuple.joinWith(righttuple);
                                outbatch.add(outtuple);

                                if (outbatch.isFull()) {
                                    if (i == leftTupleSize - 1 && j == rightbatch.size() - 1) {  //case 1
                                        lcurs = 0;
                                        rcurs = 0;
                                    } else if (i != leftTupleSize - 1 && j == rightbatch.size() - 1) {  //case 2
                                        lcurs = i + 1;
                                        rcurs = 0;
                                    } else if (i == leftTupleSize - 1 && j != rightbatch.size() - 1) {  //case 3
                                        lcurs = i;
                                        rcurs = j + 1;
                                    } else {
                                        lcurs = i;
                                        rcurs = j + 1;
                                    }
                                    return outbatch;
                                }
                            }
                        }
                        rcurs = 0;
                    }
                    lcurs = 0;
                } catch (EOFException e) {
                    try {
                        in.close();
                    } catch (IOException io) {
                        System.out.println("BlockNestedJoin: Error in reading temporary file");
                    }
                    eosr = true;
                } catch (ClassNotFoundException c) {
                    System.out.println("BlockNestedJoin: Error in deserialising temporary file ");
                    System.exit(1);
                } catch (IOException io) {
                    System.out.println("BlockNestedJoin: Error in reading temporary file");
                    System.exit(1);
                }
            }
        }
        return outbatch;
    }

}

